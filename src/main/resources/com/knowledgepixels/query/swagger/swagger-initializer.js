window.onload = function() {
  //<editor-fold desc="Changeable Configuration Block">

  // the following lines will be replaced by docker/configurator, when it runs in a docker-container
  var params = new URLSearchParams(window.location.search);
  var specUrl = params.get('url') || "https://petstore.swagger.io/v2/swagger.json";
  var apiVersion = params.get('api-version'); // TODO rename to '_api_version' for consistency
  if (apiVersion && specUrl.indexOf('api-version=') === -1) {
    specUrl += (specUrl.indexOf('?') !== -1 ? '&' : '?') + 'api-version=' + encodeURIComponent(apiVersion);
  }
  var nanopub = params.get('_nanopub_trig');
  if (nanopub && specUrl.indexOf('_nanopub_trig=') === -1) {
    specUrl += (specUrl.indexOf('?') !== -1 ? '&' : '?') + '_nanopub_trig=' + encodeURIComponent(nanopub);
  }

  window.ui = SwaggerUIBundle({
    url: specUrl,
    dom_id: '#swagger-ui',
    deepLinking: true,
    requestInterceptor: function(req) {
      if (nanopub) {
        var separator = req.url.indexOf('?') !== -1 ? '&' : '?';
        req.url += separator + '_nanopub_trig=' + encodeURIComponent(nanopub);
      }
      return req;
    },
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
