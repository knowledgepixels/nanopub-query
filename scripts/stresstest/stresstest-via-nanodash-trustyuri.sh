#!/usr/bin/env bash

curl --silent https://nanodash.np.trustyuri.net/userlist \
  | grep -Po 'https://orcid.org/[0-9-X]+' \
  | sed -r 's_(.*)_echo -n "."; curl --silent --output /dev/null https://nanodash.np.trustyuri.net/user?id=\1_' \
  | bash

echo ""
echo "Finished"
