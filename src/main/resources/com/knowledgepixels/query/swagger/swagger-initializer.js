window.onload = function() {
  //<editor-fold desc="Changeable Configuration Block">

  // the following lines will be replaced by docker/configurator, when it runs in a docker-container
  var params = new URLSearchParams(window.location.search);
  var specUrl = params.get('url') || "https://petstore.swagger.io/v2/swagger.json";
  var apiVersion = params.get('api-version');
  if (apiVersion && specUrl.indexOf('api-version=') === -1) {
    specUrl += (specUrl.indexOf('?') !== -1 ? '&' : '?') + 'api-version=' + encodeURIComponent(apiVersion);
  }

  window.ui = SwaggerUIBundle({
    url: specUrl,
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [
      SwaggerUIBundle.presets.apis,
      SwaggerUIStandalonePreset
    ],
    plugins: [
      SwaggerUIBundle.plugins.DownloadUrl
    ],
    layout: "StandaloneLayout"
  });

  //</editor-fold>
};
