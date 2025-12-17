let publishCmd = `
docker build -t "$IMAGE_NAME:\${nextRelease.version}" -t "$IMAGE_NAME:latest" .
docker push --all-tags "$IMAGE_NAME"
`
let config = require('semantic-release-preconfigured-conventional-commits');
config.tagFormat = 'nanopub-query-${version}'
config.branches = ['release']
config.plugins.push(
  [
    "@terrestris/maven-semantic-release",
    {
      "settingsPath": "./settings.xml",
      "updateSnapshotVersion": true,
      "mvnw": true
    }
  ],
  [
    "@semantic-release/exec",
    {
      "publishCmd": publishCmd
    }
  ],
  "@semantic-release/github",
  "@semantic-release/git"
)
module.exports = config