#!/usr/bin/env bash

curl --silent https://nanodash.knowledgepixels.com/userlist \
  | grep -Po 'https://orcid.org/[0-9-X]+' \
  | sed -r 's_(.*)_echo -n "."; curl --silent --output /dev/null https://nanodash.knowledgepixels.com/user?id=\1_' \
  | bash

echo ""
echo "Finished"
