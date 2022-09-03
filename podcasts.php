<?php // -*- php -*-
  $fh = fopen("../podcasts.cfg", "r");
  if (!$fh) {
    trigger_error("open failed in |" . getcwd() . "|");
    exit;
  }
  $dir = trim(fgets($fh));
  $pass = trim(fgets($fh));
  fclose($fh);

  # Password check.
  if (!isset($_GET["p"])) {
    trigger_error("Access denied");
    exit;
  }
  if ($_GET["p"] != $pass) {
    trigger_error("Access denied: |" . $_GET["p"] . "|");
    exit;
  }

  if (!chdir($dir)) {
    trigger_error("chdir '$dir' failed in |" . getcwd() . "|");
    exit;
  }

  if (isset($_GET["get"])) {
    $id = $_GET["get"];

    if (strstr($id, "/") !== false) {
      trigger_error("Invalid ID '$id'");
      exit;
    }
    $file = $id . ".mp3";
    if (!file_exists($file)) {
      trigger_error("File does not exist '$file'");
      exit;
    }

    header("Content-Description: File Transfer");
    header("Content-Type: application/octet-stream");
    header("Content-Disposition: attachment; filename=" . basename($file));
    header("Content-Transfer-Encoding: binary");
    header("Expires: 0");
    header("Cache-Control: must-revalidate");
    header("Pragma: public");
    header("Content-Length: " . filesize($file));
    readfile($file);
    exit;
  }

  if (isset($_GET["rm"])) {
    $id = $_GET["rm"];

    if (strstr($id, "/") !== false) {
      trigger_error("Invalid ID '$id'");
      exit;
    }
    unlink($id . ".mp3");
    unlink($id . ".tag");

    header("Content-type: text/plain");
    echo "OK\n";
    exit;
  }

  $dh = opendir(".");
  if (!$dh) {
    trigger_error("opendir failed");
    exit;
  }
  $tags = array();
  while (($tag = readdir($dh)) !== false) {
    if (substr($tag, -4) != ".tag")
      continue;
    if (substr($tag, 0, 1) == ".")
      continue;
    $id = substr($tag, 0, -4);
    if (!file_exists($id . ".mp3"))
      continue;
    $tags[] = $tag;
  }
  closedir($dh);

  // remaining seconds
  if (isset($_GET["r"]))
    file_put_contents("polled", $_GET["r"] . " OK\n");

  if (isset($_GET["polled"])) {
    header("Content-type: text/plain");

    echo count($tags), " ", file_get_contents("polled");
    exit;
  }

  $since = false;
  if (isset($_GET["s"]))
    $since = $_GET["s"];

  header("Content-type: text/plain");
  $newest = -1;
  foreach ($tags as $tag) {
    $mtime = filemtime($tag);
    $newest = max($newest, $mtime);
    if ($since === false || $mtime > $since) {
      readfile($tag);
      echo "\n";
    }
  }
  echo "OK\t$newest\n";
?>
