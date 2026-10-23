<?php // -*- php -*-
  function get_tags() {
    $tags = array();
    $dh = opendir(".");
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
    return $tags;
  }

  header("Content-type: text/plain");

  $fh = fopen("../podcasts.cfg", "r");
  if (!$fh) {
    echo "ERROR: open failed in |" . getcwd() . "|\n";
    exit;
  }
  $dir = trim(fgets($fh));
  $pass = trim(fgets($fh));
  fclose($fh);

  if (!chdir($dir)) {
    echo "ERROR: chdir '$dir' failed in |" . getcwd() . "|\n";
    exit;
  }

  // Don't require a password for 'polled' so pod-poll doesn't need it
  if (isset($_GET["polled"])) {
    header("Content-type: text/plain");
    echo filemtime("polled"), " ", count(get_tags()), " ", file_get_contents("polled");
    exit;
  }

  // Password check.
  if (!isset($_GET["p"])) {
    echo "ERROR: Access denied\n";
    exit;
  }
  if ($_GET["p"] != $pass) {
    echo "ERROR: Access denied\n";
    exit;
  }

  if (isset($_GET["get"])) {
    $id = $_GET["get"];

    if (strstr($id, "/") !== false) {
      echo "ERROR: Invalid ID '$id'\n";
      exit;
    }
    $file = $id . ".mp3";
    if (!file_exists($file)) {
      echo "ERROR: File does not exist '$file'\n";
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
      echo "ERROR: Invalid ID '$id'\n";
      exit;
    }
    unlink($id . ".mp3");
    unlink($id . ".tag");

    header("Content-type: text/plain");
    echo "OK\n";
    exit;
  }

  $tags = get_tags();

  // remaining seconds
  if (isset($_GET["r"]))
    file_put_contents("polled", $_GET["r"] . " OK\n");

  $since = false;
  if (isset($_GET["s"]))
    $since = $_GET["s"];

  header("Content-type: text/plain");
  $newest = -1;
  foreach ($tags as $tag) {
    $mtime = filemtime($tag);
    $newest = max($newest, $mtime);
    if ($since === false || $mtime > $since) {
      // file_put_contents("log", "send newest=$newest mtime=$mtime tag=$tag\n", FILE_APPEND);
      readfile($tag);
      echo "\n";
    }
  }
  echo "OK\t$newest\n";
?>
