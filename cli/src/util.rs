use sonicd::{Query, Result, Error, authenticate};
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::process::Command;
use std::fs::OpenOptions;
use std::str::FromStr;
use std::string::ToString;
use std::env;
use std::path::PathBuf;
use serde_json::Value;
use regex::Regex;
use std::collections::BTreeMap;

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ClientConfig {
  pub sonicd: String,
  pub http_port: u16,
  pub tcp_port: u16,
  pub sources: BTreeMap<String, Value>,
  pub auth: Option<String>
}

impl ClientConfig {
  pub fn empty() -> ClientConfig {
    ClientConfig {
      sonicd: "0.0.0.0".to_string(),
      http_port: 9111,
      tcp_port: 10001,
      sources: BTreeMap::new(),
      auth: None
    }
  }
}

static DEFAULT_EDITOR: &'static str = "vim";

pub fn get_env_var(var: &'static str) -> Result<String> {

  ::std::env::var(var).map_err(|e| {
    Error::OtherError(format!("could not get env var {}: {}", var, e.to_string()))
  })
}

fn write_config(config: &ClientConfig, path: &PathBuf) -> Result<()> {
  debug!("overwriting or creating new configuration file with {:?}",
         config);
  match OpenOptions::new().truncate(true).create(true).write(true).open(path) {
    Ok(mut f) => {
      let encoded = ::serde_json::to_string_pretty(config)
        .map_err(|e| {
          format!("error when encoding JSON to config file: {}", e)
        })
      .unwrap();
      f.write_all(encoded.as_bytes())
        .map_err(|e| format!("error when writing to config file: {}", e))
        .unwrap();
      debug!("write success to config file {:?}", path);
      Ok(())
    }
    Err(e) => Err(Error::OtherError(format!("write_config: {}", e.to_string()))),
  }
}

fn get_config_path() -> PathBuf {
  let mut sonicrc = env::home_dir().expect("can't find your home folder");
  sonicrc.push(".sonicrc");
  sonicrc
}

fn edit_file(path: &PathBuf) -> Result<String> {
  let editor: String = get_env_var("EDITOR")
    .unwrap_or_else(|_| DEFAULT_EDITOR.to_owned());
  let mut cmd = Command::new(&editor);

  try!(cmd.arg(path.to_str().unwrap())
       .status()
       .map_err(|e| Error::OtherError(format!("edit_file: {}", e.to_string()))));

  let mut f = try!(OpenOptions::new().read(true).open(path)
                   .map_err(|e| Error::OtherError(format!("open: {}", e))));
  let mut body = String::new();
  f.read_to_string(&mut body).unwrap();

  Ok(body)
}

pub fn read_file_contents(path: &PathBuf) -> Result<String> {

  let mut file = try!(File::open(&path).map_err(|e| {
    Error::OtherError(format!("could not open file in '{:?}': {}", &path, e))
  }));
  let mut contents = String::new();
  try!(file.read_to_string(&mut contents)
       .map_err(|e| Error::OtherError(format!("could not read file in '{:?}': {}", &path, e))));

  return Ok(contents);
}

pub fn read_config(path: &PathBuf) -> Result<ClientConfig> {

  let contents = try!(read_file_contents(&path));

  ::serde_json::from_str::<ClientConfig>(&contents.to_string())
    .map_err(|e| Error::OtherError(format!("Could not deserialize config file: {}", e)))
}


/// Sources .sonicrc user config or creates new and prompts user to edit it
pub fn get_default_config() -> Result<ClientConfig> {

  let path = get_config_path();

  debug!("trying to get configuration in path {:?}", path);

  match fs::metadata(&path) {
    Ok(ref cfg_attr) if cfg_attr.is_file() => {
      debug!("found a file in {:?}", path);
      read_config(&path)
    }
    _ => {
      let mut stdout = io::stdout();
      stdout.write(b"It looks like it's the first time you're using the sonic CLI. Let's configure a few things. Press any key to continue").unwrap();
      stdout.flush().unwrap();
      let mut input = String::new();
      match io::stdin().read_line(&mut input) {
        Ok(_) => {
          try!(write_config(&ClientConfig::empty(), &path));
          let contents = try!(edit_file(&path));
          let c: ClientConfig =
            ::serde_json::from_str(&contents).unwrap();
          println!("successfully saved configuration in $HOME/.sonicrc");
          Ok(c)
        }
        Err(error) => Err(Error::OtherError(error.to_string())),
      }
    }
  }
}


/// Splits strings by character '='
///
/// # Examples
/// ```
/// use libsonic::util::split_key_value;
///
/// let vars = vec!["TABLE=account".to_string(), "DATECUT=2015-09-13".to_string()];
/// let result = split_key_value(&vars).unwrap();
///
/// assert_eq!(result[0].0, "TABLE");
/// assert_eq!(result[0].1, "account");
/// assert_eq!(result[1].0, "DATECUT");
/// assert_eq!(result[1].1, "2015-09-13");
/// ```
///
/// It returns an error if string doesn't contain '='.
///
/// # Failures
/// ```
/// use libsonic::util::split_key_value;
///
/// let vars = vec!["key val".to_string()];
/// split_key_value(&vars);
/// ```
pub fn split_key_value(vars: &Vec<String>) -> Result<Vec<(String, String)>> {
  debug!("parsing variables {:?}", vars);
  let mut m: Vec<(String, String)> = Vec::new();
  for var in vars.iter() {
    if var.contains("=") {
      let mut split = var.split("=");
      m.push((split.next().unwrap().to_string(),
      split.next().unwrap().to_string()));
    } else {
      return Err(Error::OtherError(format!("Cannot split {}. It should follow format \
                                               'key=value'",
                                               var)));
    }
  }
  debug!("Successfully parsed parsed variables {:?} into {:?}",
         vars,
         &m);
  return Ok(m);
}


/// Attempts to inject all variables to the given template:
///
/// # Examples
/// ```
/// use libsonic::util::inject_vars;
///
/// let query = "select count(*) from ${TABLE} where dt > '${DATECUT}' and dt <= \
///     date_sub('${DATECUT}', 30);".to_string();
///
/// let vars = vec![("TABLE".to_string(), "accounts".to_string()),
///     ("DATECUT".to_string(), "2015-01-02".to_string())];
///
/// assert_eq!(inject_vars(&query, &vars).unwrap(),
///     "select count(*) from accounts where dt > '2015-01-02' and dt <= \
///     date_sub('2015-01-02', 30);".to_string());
///
/// ```
///
/// It will return an Error if there is a discrepancy between variables and template
///
/// # Failures
/// ```
/// use libsonic::util::inject_vars;
///
/// let query = "select count(*) from hamburgers".to_string();
/// let vars = vec![("TABLE".to_string(), "accounts".to_string())];
/// inject_vars(&query, &vars);
///
/// let query = "select count(*) from ${TABLE} where ${POTATOES}".to_string();
/// let vars = vec![("TABLE".to_string(), "accounts".to_string())];
/// inject_vars(&query, &vars);
///
/// ```
pub fn inject_vars(template: &str, vars: &Vec<(String, String)>) -> Result<String> {
  debug!("injecting variables {:?} into '{:?}'", vars, template);
  let mut q = String::from_str(template).unwrap();
  for var in vars.iter() {
    let k = "${".to_string() + &var.0 + "}";
    if !q.contains(&k) {
      return Err(Error::OtherError(format!("{} not found in template", k)));
    } else {
      q = q.replace(&k, &var.1);
    }
  }

  debug!("injected all variables: '{:?}'", &q);

  // check if some variables were left un-injected
  let re = Regex::new(r"(\$\{.*\})").unwrap();
  if re.is_match(&q) {
    Err(Error::OtherError("Some variables remain uninjected".to_string()))
  } else {
    Ok(q)
  }
}

pub fn build(alias: String, mut sources: BTreeMap<String, Value>, auth: Option<String>, raw_query: String) -> Result<Query> {

  let clean = sources.remove(&alias);

  let source_config = match clean {
    Some(o@Value::Object(_)) => o,
    None => Value::String(alias),
    _ => {
      return Err(Error::OtherError(format!("source '{}' config is not an object", &alias)));
    },
  };

  let query = Query {
    id: None,
    trace_id: None,
    auth: auth,
    query: raw_query,
    config: source_config,
  };

  Ok(query)
}

pub fn login(host: &str, tcp_port: &u16) -> Result<()> {

  let user: String = try!(get_env_var("USER"));

  try!(io::stdout().write(b"Enter key: ")
       .map_err(|e| Error::OtherError(e.to_string())));

  io::stdout().flush().unwrap();

  let mut key = String::new();

  match io::stdin().read_line(&mut key) {
    Ok(_) => {
      let token = try!(authenticate(user, key.trim().to_owned(), host, tcp_port));
      let path = get_config_path();
      let config = try!(read_config(&path));

      let new_config = ClientConfig { auth: Some(token), ..config };
      try!(write_config(&new_config, &path));

      println!("OK");
      Ok(())
    },
    Err(e) => Err(Error::OtherError(e.to_string())),
  }
}
