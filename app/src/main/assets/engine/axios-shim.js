// axios-shim.js — StreamOne provider engine
//
// A minimal axios-compatible client for Vega provider bundles. Providers use
// the `axios` object injected through providerContext; this shim implements
// the subset of the axios API providers actually rely on:
//
//   axios.get(url, config)            axios.post(url, data, config)
//   axios.put / patch / delete / head / options / request(config)
//   axios.create(defaults)            axios.defaults
//   axios.interceptors.request/response.use(fn)   (basic, in-order)
//   config: { url, method, headers, data, params, timeout, responseType,
//             validateStatus }
//
// Every call is executed SYNCHRONOUSLY through the native bridge function
//   __httpRequest(jsonString) -> jsonString
// (defined by provider-loader.js on top of the bridge object that
// ProviderEngine installs via the QuickJS binding) and wrapped in a
// Promise, so provider `await axios.get(...)` code works unchanged.
//
// Native request JSON : { url, method, headers, data(string|null), timeout, responseType }
// Native reply JSON   : { status, statusText, headers, body, bodyBase64? , error? }
//
// Behaviour notes (matching real axios):
//  - JSON-looking string bodies are auto-parsed into objects.
//  - Non-2xx statuses reject with an error that carries `.response`,
//    unless config.validateStatus says otherwise.
//  - `signal` (AbortSignal) is accepted and ignored: native calls are
//    synchronous, so there is nothing in flight to abort.

(function () {
  'use strict';

  function isObject(v) {
    return v !== null && typeof v === 'object';
  }

  function serializeParams(params) {
    var parts = [];
    if (!isObject(params)) return '';
    Object.keys(params).forEach(function (key) {
      var value = params[key];
      if (value === undefined || value === null) return;
      if (Array.isArray(value)) {
        value.forEach(function (item) {
          parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(item));
        });
      } else {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
      }
    });
    return parts.join('&');
  }

  function buildFullUrl(url, params) {
    var query = serializeParams(params);
    if (!query) return url;
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + query;
  }

  function findHeader(headers, name) {
    if (!isObject(headers)) return undefined;
    var lower = name.toLowerCase();
    var keys = Object.keys(headers);
    for (var i = 0; i < keys.length; i++) {
      if (keys[i].toLowerCase() === lower) return headers[keys[i]];
    }
    return undefined;
  }

  function mergeHeaders(base, extra) {
    var out = {};
    if (isObject(base)) Object.keys(base).forEach(function (k) { out[k] = base[k]; });
    if (isObject(extra)) Object.keys(extra).forEach(function (k) { out[k] = extra[k]; });
    return out;
  }

  function mergeConfig(defaults, config) {
    var out = {};
    Object.keys(defaults || {}).forEach(function (k) { out[k] = defaults[k]; });
    Object.keys(config || {}).forEach(function (k) {
      if (config[k] !== undefined) out[k] = config[k];
    });
    out.headers = mergeHeaders(defaults && defaults.headers, config && config.headers);
    return out;
  }

  function createError(message, config, response) {
    var err = new Error(message);
    err.config = config;
    err.isAxiosError = true;
    if (response) {
      err.response = response;
      err.status = response.status;
    }
    return err;
  }

  function InterceptorManager() {
    this.handlers = [];
  }
  InterceptorManager.prototype.use = function (onFulfilled, onRejected) {
    this.handlers.push({ fulfilled: onFulfilled, rejected: onRejected });
    return this.handlers.length - 1;
  };
  InterceptorManager.prototype.eject = function (id) {
    if (this.handlers[id]) this.handlers[id] = null;
  };
  InterceptorManager.prototype.forEach = function (fn) {
    this.handlers.forEach(function (h) { if (h) fn(h); });
  };

  function dispatchRequest(config) {
    var method = (config.method || 'get').toLowerCase();
    var url = config.url || '';
    if (config.baseURL && !/^https?:\/\//i.test(url)) {
      url = config.baseURL.replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '');
    }
    url = buildFullUrl(url, config.params);

    var headers = mergeHeaders({}, config.headers);
    var body = null;
    var data = config.data;
    if (data !== undefined && data !== null && method !== 'get' && method !== 'head') {
      if (typeof data === 'string') {
        body = data;
      } else if (typeof URLSearchParams !== 'undefined' && data instanceof URLSearchParams) {
        body = data.toString();
        if (!findHeader(headers, 'Content-Type')) {
          headers['Content-Type'] = 'application/x-www-form-urlencoded';
        }
      } else if (isObject(data)) {
        var contentType = findHeader(headers, 'Content-Type') || '';
        if (String(contentType).indexOf('application/x-www-form-urlencoded') >= 0) {
          body = serializeParams(data);
        } else {
          body = JSON.stringify(data);
          if (!contentType) headers['Content-Type'] = 'application/json';
        }
      } else {
        body = String(data);
      }
    }

    var nativeRequest = {
      url: url,
      method: method.toUpperCase(),
      headers: headers,
      data: body,
      timeout: typeof config.timeout === 'number' ? config.timeout : 0,
      responseType: config.responseType || ''
    };

    var raw;
    try {
      raw = __httpRequest(JSON.stringify(nativeRequest));
    } catch (e) {
      return Promise.reject(createError('Native HTTP bridge error: ' + (e && e.message ? e.message : e), config));
    }

    var nativeResponse;
    try {
      nativeResponse = JSON.parse(raw);
    } catch (e) {
      return Promise.reject(createError('Native HTTP bridge returned invalid JSON', config));
    }

    if (nativeResponse.error && !nativeResponse.status) {
      var netErr = createError(String(nativeResponse.error), config);
      netErr.code = 'ERR_NETWORK';
      return Promise.reject(netErr);
    }

    var responseData = nativeResponse.body;
    if (nativeResponse.bodyBase64) {
      // Binary payloads are handed to the provider as a base64 string;
      // providers in practice only request text/JSON.
      responseData = nativeResponse.body;
    } else if (
      typeof responseData === 'string' &&
      config.responseType !== 'text' &&
      config.responseType !== 'arraybuffer' &&
      config.responseType !== 'blob'
    ) {
      var trimmed = responseData.replace(/^\s+/, '');
      if (trimmed.charAt(0) === '{' || trimmed.charAt(0) === '[') {
        try { responseData = JSON.parse(trimmed); } catch (e) { /* keep raw string */ }
      }
    }

    var response = {
      data: responseData,
      status: nativeResponse.status,
      statusText: nativeResponse.statusText || '',
      headers: nativeResponse.headers || {},
      config: config,
      request: {}
    };

    var validateStatus = config.validateStatus || function (status) {
      return status >= 200 && status < 300;
    };
    if (validateStatus(nativeResponse.status)) {
      return Promise.resolve(response);
    }
    var err = createError('Request failed with status code ' + nativeResponse.status, config, response);
    err.code = nativeResponse.status >= 500 ? 'ERR_BAD_RESPONSE' : 'ERR_BAD_REQUEST';
    return Promise.reject(err);
  }

  function createInstance(defaults) {
    var instance = {
      defaults: defaults || {},
      interceptors: {
        request: new InterceptorManager(),
        response: new InterceptorManager()
      }
    };

    instance.request = function (configOrUrl, extraConfig) {
      var config;
      if (typeof configOrUrl === 'string') {
        config = mergeConfig(instance.defaults, extraConfig || {});
        config.url = configOrUrl;
      } else {
        config = mergeConfig(instance.defaults, configOrUrl || {});
      }
      var chain = Promise.resolve(config);
      instance.interceptors.request.forEach(function (handler) {
        chain = chain.then(handler.fulfilled, handler.rejected);
      });
      chain = chain.then(function (resolvedConfig) {
        return dispatchRequest(resolvedConfig);
      });
      instance.interceptors.response.forEach(function (handler) {
        chain = chain.then(handler.fulfilled, handler.rejected);
      });
      return chain;
    };

    ['get', 'delete', 'head', 'options'].forEach(function (method) {
      instance[method] = function (url, config) {
        var merged = mergeConfig({}, config || {});
        merged.method = method;
        merged.url = url;
        return instance.request(merged);
      };
    });

    ['post', 'put', 'patch'].forEach(function (method) {
      instance[method] = function (url, data, config) {
        var merged = mergeConfig({}, config || {});
        merged.method = method;
        merged.url = url;
        merged.data = data;
        return instance.request(merged);
      };
    });

    instance.getUri = function (config) {
      var merged = mergeConfig(instance.defaults, config || {});
      return buildFullUrl(merged.url || '', merged.params);
    };

    return instance;
  }

  var axios = createInstance({});
  axios.create = function (defaults) {
    return createInstance(mergeConfig(axios.defaults, defaults || {}));
  };
  axios.isAxiosError = function (payload) {
    return !!(payload && payload.isAxiosError);
  };
  axios.all = function (promises) { return Promise.all(promises); };
  axios.spread = function (callback) {
    return function (array) { return callback.apply(null, array); };
  };

  globalThis.__axiosShim = axios;
})();
