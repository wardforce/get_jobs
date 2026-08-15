(function () {
  "use strict";

  var noisyExtension = "chrome-extension://acfcbfkjgnbfglpnlfipdohfdgpgpogh/";

  function comesFromNoisyExtension(value) {
    if (!value) return false;
    if (typeof value === "string") return value.indexOf(noisyExtension) !== -1;
    return comesFromNoisyExtension(value.stack) || comesFromNoisyExtension(value.message);
  }

  window.addEventListener(
    "error",
    function (event) {
      if (comesFromNoisyExtension(event.filename) || comesFromNoisyExtension(event.error)) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    },
    true
  );

  window.addEventListener(
    "unhandledrejection",
    function (event) {
      if (comesFromNoisyExtension(event.reason)) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    },
    true
  );
})();
