#!/usr/bin/env bash

curl --silent 'https://nanodash.knowledgepixels.com/userlist' \
  | grep -Po 'https://orcid.org/[0-9X-]+' \
  | sed 's_^_echo -n "."; curl --silent --output /dev/null https://nanodash.knowledgepixels.com/user?id=_' \
  | bash

echo ""
echo "Finished"
