#!/usr/bin/env bash

cd "$( dirname "${BASH_SOURCE[0]}" )"
cd ..

echo "Subject,Predicate,Object,Graph,Group,Comment" \
  > doc/admin-triple-table.csv

cat src/main/java/com/knowledgepixels/query/NanopubLoader.java \
  | grep '@ADMIN-TRIPLE-TABLE@' \
  | sed -r 's/^.*@ADMIN-TRIPLE-TABLE@ (.*)$/\1/' \
  | sed 's/, /,/g' \
  >> doc/admin-triple-table.csv

(
  echo '<!DOCTYPE html>'
  echo '<html lang='en'>'
  echo '<head>'
  echo '<title>Admin Triple Table</title>'
  echo '<meta charset="utf-8">'
  echo '<link rel="stylesheet" href="/style.css">'
  echo '</head>'
  echo '<body>'
  echo '<table>'
) > doc/admin-triple-table.html
HEADER=true
while read LINE; do
  if $HEADER; then
    echo "<tr><th>${LINE//,/</th><th>}</th></tr>" >> doc/admin-triple-table.html
    HEADER=false
    continue
  fi
  echo "<tr><td>${LINE//,/</td><td>}</td></tr>" >> doc/admin-triple-table.html
done < doc/admin-triple-table.csv
echo '</table>' >> doc/admin-triple-table.html

