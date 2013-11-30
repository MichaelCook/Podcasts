<?php // -*- php -*-
  if (!chdir("/local/podcasts")) {
    trigger_error("chdir failed");
    exit;
  }

  # Password check.
  if (!isset($_GET["p"])) {
    trigger_error("Access denied");
    exit;
  }
  if ($_GET["p"] != "sekret") {
    trigger_error("Access denied: |" . $_GET["p"] . "|");
    exit;
  }

  if (isset($_GET["get"])) {
    $file = $_GET["get"];

    if (strstr($file, "/") !== false) {
      trigger_error("File name '$file' contains path separator");
      exit;
    }
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
    $file = $_GET["rm"];

    if (strstr($file, "/") !== false) {
      trigger_error("File name '$file' contains path separator");
      exit;
    }
    header("Content-type: text/plain");

//    if (!unlink($file)) {
//      trigger_error("Couldn't unlink $file");
//      exit;
//    }

    putenv("PATH=/home/mcook/bin:/sbin:/bin:/usr/bin:/usr/sbin");
    system("del " . escapeshellarg($file), $exit);
    if ($exit) {
      trigger_error("Trouble deleting $file: $exit");
      exit;
    }

    echo "OK\n";
    exit;
  }

  $dh = opendir(".");
  if (!$dh) {
    trigger_error("opendir failed");
    exit;
  }
  header("Content-type: text/plain");
  while (($file = readdir($dh)) !== false) {
    if (substr($file, -4) != ".mp3")
      continue;
    if (substr($file, 0, 1) == "#")
      continue;
    echo filesize($file), "\t", $file, "\n";
  }
  closedir($dh);
  echo "OK\n";
?>
