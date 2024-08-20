#!/usr/bin/env bash

curl --silent 'http://localhost:9393/' \
  | grep -Po '/page/[a-zA-Z0-9/]+' \
  | sed -r 's_^/page(.*)$_echo "\1"; curl --silent '\''http://localhost:9393/repo\1?query=select%20%2A%20where%20%7B%20graph%20%3Chttps%3A%2F%2Fw3id.org%2Fnp%2FRAdxdsL5vtExmiaydCI0yJCCoE5lkNksGr46KPEJUR37k%23assertion%3E%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D%20%7D'\''_' \
  | bash

echo ""
echo "Finished"
