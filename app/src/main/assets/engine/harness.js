// harness.js — StreamOne provider-engine WebView harness.
//
// Native port of the Vega app's sandbox architecture (see
// vega-app src/lib/sandbox/runtime/sandboxWorker.ts): provider bundles run
// in this page's JS realm with NO direct network access of their own —
// window.fetch is replaced and axios gets a custom adapter, both forwarding
// every request over an RPC bridge to Kotlin, which performs it with OkHttp
// and a per-provider cookie jar. Results come back through
// __rpcResolve/__rpcReject; provider-call results go out through
// __nativeRpc.result(callId, json).
//
// Kotlin injects one bridge object before the page loads:
//
//   window.__nativeRpc = {
//     rpc(id, payloadJson),          // async RPC: http / openWebView / crypto
//     result(callId, json),          // provider call result sink
//     ready(),                       // harness bootstrap complete
//     log(message),                  // console sink
//     kvGet(providerKey, key) -> string ('' when absent),   // synchronous
//     kvSet(providerKey, key, valueJson),
//     kvDelete(providerKey, key) -> 'true' | 'false',
//     kvKeys(providerKey) -> string (JSON array),
//     kvClear(providerKey),
//     getBaseUrl(providerValue) -> string ('' when unset),
//     cookiesFor(providerKey, url) -> string (JSON object)
//   }
//
// Load order (harness.html): axios.bundle.js, cheerio.bundle.js, harness.js.

(function () {
  'use strict';

  var nativeRpc = window.__nativeRpc || null;

  function bridgeError(what) {
    return new Error('StreamOne native bridge unavailable for ' + what);
  }

  // ------------------------------------------------------------ base64 utils
  function bytesToBase64(bytes) {
    var binary = '';
    var chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode.apply(
        null,
        bytes.subarray(i, Math.min(i + chunk, bytes.length))
      );
    }
    return btoa(binary);
  }

  function base64ToBytes(base64) {
    var binary = atob(String(base64 || ''));
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function errorMessage(e) {
    if (!e) return 'unknown error';
    if (e.stack) return String(e.stack);
    if (e.message) return String(e.message);
    return String(e);
  }

  // ---------------------------------------------------------------- console
  var logCount = 0;
  function relayLog(level) {
    return function () {
      try {
        if (nativeRpc && logCount++ < 200) {
          var parts = [];
          for (var i = 0; i < arguments.length; i++) {
            var a = arguments[i];
            if (typeof a === 'string') parts.push(a);
            else {
              try { parts.push(JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
            }
          }
          nativeRpc.log('[' + level + '] ' + parts.join(' ').slice(0, 2000));
        }
      } catch (e) { /* logging must never break a provider */ }
    };
  }
  var providerConsole = {
    log: relayLog('log'),
    info: relayLog('info'),
    debug: relayLog('debug'),
    warn: relayLog('warn'),
    error: relayLog('error')
  };

  // -------------------------------------------------------------------- RPC
  var nextRpcId = 0;
  var pendingRpc = new Map();

  function rpc(kind, payload) {
    return new Promise(function (resolve, reject) {
      if (!nativeRpc) {
        reject(bridgeError(kind));
        return;
      }
      var id = String(++nextRpcId);
      pendingRpc.set(id, { resolve: resolve, reject: reject });
      try {
        var body = {};
        payload = payload || {};
        for (var k in payload) body[k] = payload[k];
        body.kind = kind;
        nativeRpc.rpc(id, JSON.stringify(body));
      } catch (e) {
        pendingRpc.delete(id);
        reject(e);
      }
    });
  }

  window.__rpcResolve = function (id, resultJson) {
    var pending = pendingRpc.get(String(id));
    if (!pending) return;
    pendingRpc.delete(String(id));
    try {
      pending.resolve(resultJson ? JSON.parse(resultJson) : null);
    } catch (e) {
      pending.reject(e);
    }
  };

  window.__rpcReject = function (id, error) {
    var pending = pendingRpc.get(String(id));
    if (!pending) return;
    pendingRpc.delete(String(id));
    pending.reject(new Error(error ? String(error) : 'Native RPC failed'));
  };

  // ------------------------------------------------------------ body handling
  // Port of Vega's serializeBody (sandbox/runtime/runtimeSupport.ts).
  function serializeBody(body) {
    if (body == null) return Promise.resolve({ kind: 'none' });
    if (typeof body === 'string') return Promise.resolve({ kind: 'text', value: body });
    if (body instanceof Uint8Array) {
      return Promise.resolve({ kind: 'base64', value: bytesToBase64(body) });
    }
    if (body instanceof ArrayBuffer) {
      return Promise.resolve({ kind: 'base64', value: bytesToBase64(new Uint8Array(body)) });
    }
    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      return Promise.resolve({ kind: 'text', value: body.toString() });
    }
    if (typeof FormData !== 'undefined' && body instanceof FormData) {
      return new Response(body).arrayBuffer().then(function (buf) {
        return {
          kind: 'base64',
          value: bytesToBase64(new Uint8Array(buf)),
          contentType: new Response(body).headers.get('content-type') || undefined
        };
      });
    }
    try {
      var text = JSON.stringify(body);
      return Promise.resolve({ kind: 'text', value: text == null ? '' : text });
    } catch (e) {
      return Promise.resolve({ kind: 'text', value: String(body) });
    }
  }

  function headerEntries(source) {
    var entries = [];
    if (!source) return entries;
    if (typeof source.forEach === 'function') {
      // Headers and AxiosHeaders both expose forEach(value, key).
      try {
        source.forEach(function (value, key) { entries.push([key, value]); });
        return entries;
      } catch (e) { /* fall through to object handling */ }
    }
    if (Array.isArray(source)) {
      for (var i = 0; i < source.length; i++) {
        var pair = source[i];
        if (Array.isArray(pair) && pair.length >= 2) {
          entries.push([String(pair[0]), String(pair[1])]);
        }
      }
      return entries;
    }
    if (typeof source === 'object') {
      for (var key in source) {
        if (!Object.prototype.hasOwnProperty.call(source, key)) continue;
        var value = source[key];
        if (value != null && typeof value !== 'object') {
          entries.push([key, String(value)]);
        } else if (Array.isArray(value)) {
          for (var j = 0; j < value.length; j++) entries.push([key, String(value[j])]);
        }
      }
    }
    return entries;
  }

  // ------------------------------------------------------------- fetch shim
  // The provider key whose call is currently executing. Kotlin serialises
  // provider calls, so at most one is active at a time; the RPC layer uses
  // it to pick the right cookie jar.
  var activeProviderKey = '';

  function sandboxFetch(input, init) {
    init = init || {};
    var url = typeof input === 'string' ? input : String((input && input.url) || input);
    var merged = headerEntries(input && input.headers).concat(headerEntries(init.headers));
    var bodyPromise = serializeBody(
      init.body !== undefined ? init.body : (input && input.body)
    );
    return bodyPromise.then(function (serializedBody) {
      return rpc('http', {
        providerKey: activeProviderKey,
        url: url,
        method: (init.method || (input && input.method) || 'GET').toUpperCase(),
        headers: merged,
        body: serializedBody,
        redirect: init.redirect === 'manual' ? 'manual' : 'follow',
        timeoutMs: init.timeoutMs || 0
      });
    }).then(function (response) {
      var bytes = base64ToBytes(response.bodyBase64);
      var nullBody = response.status === 204 || response.status === 304;
      var result = new Response(nullBody ? null : bytes.buffer, {
        status: response.status,
        statusText: response.statusText || '',
        headers: response.headers || []
      });
      try {
        Object.defineProperty(result, 'url', { value: response.url || url });
        // Headers merges duplicate names, including Set-Cookie. Keep the
        // original pairs for axios providers that expect one cookie per
        // array entry (same trick as Vega's sandbox).
        Object.defineProperty(result, 'rawHeaders', { value: response.headers || [] });
      } catch (e) { /* non-fatal */ }
      return result;
    });
  }

  window.fetch = sandboxFetch;

  // ---------------------------------------------------------- axios adapter
  var axiosLib = globalThis.axios;
  if (!axiosLib) {
    if (nativeRpc) nativeRpc.log('[error] axios bundle failed to load');
    throw new Error('axios bundle failed to load');
  }

  function sandboxAxiosAdapter(config) {
    var url = config.url || '';
    if (config.baseURL && !/^https?:/i.test(url)) {
      url = config.baseURL.replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '');
    }
    if (config.params) {
      var query = new URLSearchParams(config.params).toString();
      if (query) url += (url.indexOf('?') >= 0 ? '&' : '?') + query;
    }
    return sandboxFetch(url, {
      method: (config.method || 'get').toUpperCase(),
      headers: config.headers,
      body: config.data,
      redirect: config.maxRedirects === 0 ? 'manual' : 'follow',
      timeoutMs: config.timeout || 0
    }).then(function (response) {
      var AxiosHeaders = axiosLib.AxiosHeaders;
      var responseHeaders = AxiosHeaders ? new AxiosHeaders() : {};
      response.headers.forEach(function (value, key) {
        if (typeof responseHeaders.set === 'function') responseHeaders.set(key, value);
        else responseHeaders[key] = value;
      });
      var raw = response.rawHeaders || [];
      var setCookies = [];
      for (var i = 0; i < raw.length; i++) {
        if (String(raw[i][0]).toLowerCase() === 'set-cookie') setCookies.push(raw[i][1]);
      }
      if (setCookies.length === 0) {
        for (var j = 0; j < raw.length; j++) {
          if (String(raw[j][0]).toLowerCase() === 'x-set-cookie') setCookies.push(raw[j][1]);
        }
      }
      if (setCookies.length > 0) {
        if (typeof responseHeaders.set === 'function') {
          responseHeaders.set('set-cookie', setCookies);
          responseHeaders.set('x-set-cookie', setCookies);
        } else {
          responseHeaders['set-cookie'] = setCookies;
          responseHeaders['x-set-cookie'] = setCookies;
        }
      }

      var dataPromise;
      if (config.responseType === 'arraybuffer') {
        dataPromise = response.arrayBuffer();
      } else {
        dataPromise = response.text().then(function (text) {
          if (config.responseType === 'text' || config.responseType === 'blob' ||
              config.responseType === 'stream' || config.responseType === 'document') {
            return text;
          }
          try { return text ? JSON.parse(text) : ''; } catch (e) { return text; }
        });
      }
      return dataPromise.then(function (data) {
        var result = {
          data: data,
          status: response.status,
          statusText: response.statusText,
          headers: responseHeaders,
          config: config,
          request: {
            url: url,
            responseURL: response.url,
            res: { responseUrl: response.url }
          }
        };
        var validate = config.validateStatus ||
          function (s) { return s >= 200 && s < 300; };
        if (!validate(response.status)) {
          var error = new Error('Request failed with status code ' + response.status);
          error.response = result;
          error.config = config;
          error.isAxiosError = true;
          error.code = response.status >= 500 ? 'ERR_BAD_RESPONSE' : 'ERR_BAD_REQUEST';
          throw error;
        }
        return result;
      });
    }, function (err) {
      // Transport-level failure: reject axios-style so provider code that
      // inspects error.response / error.code keeps working.
      var error = err instanceof Error ? err : new Error(String(err));
      error.config = config;
      error.isAxiosError = true;
      if (!error.code) error.code = 'ERR_NETWORK';
      throw error;
    });
  }

  axiosLib.defaults.adapter = sandboxAxiosAdapter;

  // -------------------------------------------------------------- Crypto shim
  // Port of Vega's createCryptoShim (expo-crypto surface).
  var Crypto = Object.freeze({
    CryptoDigestAlgorithm: Object.freeze({
      SHA1: 'SHA-1',
      SHA256: 'SHA-256',
      SHA384: 'SHA-384',
      SHA512: 'SHA-512',
      MD5: 'MD5'
    }),
    CryptoEncoding: Object.freeze({ HEX: 'hex', BASE64: 'base64' }),
    digestStringAsync: function (algorithm, data, options) {
      return rpc('cryptoDigest', {
        algorithm: String(algorithm),
        data: String(data),
        encoding: (options && options.encoding) || 'hex'
      });
    },
    getRandomBytesAsync: function (byteCount) {
      return rpc('cryptoRandom', { byteCount: byteCount | 0 }).then(function (base64) {
        return base64ToBytes(base64);
      });
    },
    randomUUID: function () {
      var hex = '0123456789abcdef';
      var uuid = '';
      for (var i = 0; i < 36; i++) {
        if (i === 8 || i === 13 || i === 18 || i === 23) uuid += '-';
        else if (i === 14) uuid += '4';
        else if (i === 19) uuid += hex[(Math.floor(Math.random() * 16) & 0x3) | 0x8];
        else uuid += hex[Math.floor(Math.random() * 16)];
      }
      return uuid;
    }
  });

  // ------------------------------------------------------------- __awaiter
  // TypeScript's helper emitted for transpiled async functions; Vega passes
  // it into provider scope, and some bundles reference it as a free variable.
  function __awaiter(thisArg, _arguments, P, generator) {
    function adopt(value) {
      return value instanceof P ? value : new P(function (resolve) { resolve(value); });
    }
    return new (P || (P = Promise))(function (resolve, reject) {
      function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
      function rejected(value) { try { step(generator['throw'](value)); } catch (e) { reject(e); } }
      function step(result) {
        result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected);
      }
      step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
  }
  globalThis.__awaiter = __awaiter;

  // --------------------------------------------------------- provider context
  // Values copied from the Vega app (providers/headers.ts, Android branch).
  var COMMON_HEADERS = {
    'sec-ch-ua': '"Not_A Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"',
    'sec-ch-ua-mobile': '?1',
    'sec-ch-ua-platform': '"Android"',
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
    'Accept-Language': 'en-US,en;q=0.9'
  };

  var contexts = {};

  function getProviderContext(providerKey) {
    var cached = contexts[providerKey];
    if (cached) return cached;
    var kvStore = Object.freeze({
      get: function (key) {
        if (!nativeRpc) return Promise.reject(bridgeError('kvStore.get'));
        var raw = nativeRpc.kvGet(providerKey, String(key));
        if (raw === '' || raw === null || raw === undefined) return Promise.resolve(undefined);
        try { return Promise.resolve(JSON.parse(raw)); }
        catch (e) { return Promise.resolve(undefined); }
      },
      set: function (key, value) {
        if (!nativeRpc) return Promise.reject(bridgeError('kvStore.set'));
        if (value === undefined) nativeRpc.kvDelete(providerKey, String(key));
        else nativeRpc.kvSet(providerKey, String(key), JSON.stringify(value));
        return Promise.resolve();
      },
      delete: function (key) {
        if (!nativeRpc) return Promise.reject(bridgeError('kvStore.delete'));
        return Promise.resolve(nativeRpc.kvDelete(providerKey, String(key)) === 'true');
      },
      keys: function () {
        if (!nativeRpc) return Promise.reject(bridgeError('kvStore.keys'));
        try { return Promise.resolve(JSON.parse(nativeRpc.kvKeys(providerKey))); }
        catch (e) { return Promise.resolve([]); }
      },
      clear: function () {
        if (!nativeRpc) return Promise.reject(bridgeError('kvStore.clear'));
        nativeRpc.kvClear(providerKey);
        return Promise.resolve();
      }
    });
    var context = Object.freeze({
      axios: axiosLib,
      getBaseUrl: function (providerValue) {
        if (!nativeRpc) return Promise.resolve('');
        return Promise.resolve(nativeRpc.getBaseUrl(String(providerValue)) || '');
      },
      commonHeaders: COMMON_HEADERS,
      Crypto: Crypto,
      cheerio: globalThis.cheerio,
      openWebView: function (url, options) {
        return rpc('openWebView', {
          providerKey: providerKey,
          url: String(url),
          options: options || null
        });
      },
      getCookies: function (url) {
        if (!nativeRpc) return Promise.resolve({});
        try {
          return Promise.resolve(JSON.parse(nativeRpc.cookiesFor(providerKey, String(url)) || '{}'));
        } catch (e) {
          return Promise.resolve({});
        }
      },
      kvStore: kvStore
    });
    contexts[providerKey] = context;
    return context;
  }

  // ------------------------------------------------------------ module system
  var modules = {};        // '<providerKey>/<module>' -> exports object
  var providerGlobals = {}; // providerKey -> persistent scratch object

  function loadModule(providerKey, moduleName, source) {
    try {
      var exports = {};
      var module = { exports: exports };
      if (!providerGlobals[providerKey]) providerGlobals[providerKey] = {};
      var factory = new Function(
        'exports', 'module', 'require', 'console', 'Promise',
        'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
        'fetch', '__awaiter', 'providerGlobal',
        '"use strict";\n' + source +
          '\n//# sourceURL=provider-' + moduleName + '.js'
      );
      factory(
        exports,
        module,
        function () { return {}; }, // Vega's require: an empty object
        providerConsole,
        Promise,
        setTimeout, clearTimeout, setInterval, clearInterval,
        sandboxFetch,
        __awaiter,
        providerGlobals[providerKey]
      );
      modules[providerKey + '/' + moduleName] = module.exports;
      return JSON.stringify({ ok: true });
    } catch (e) {
      return JSON.stringify({ ok: false, error: errorMessage(e) });
    }
  }

  function callProvider(callId, providerKey, moduleName, exportName, argsJson) {
    (async function () {
      try {
        var mod = modules[providerKey + '/' + moduleName];
        if (!mod) throw new Error('Provider module not loaded: ' + moduleName);
        var args = {};
        if (argsJson) {
          try { args = JSON.parse(argsJson) || {}; } catch (e) { args = {}; }
        }
        activeProviderKey = providerKey;
        args.signal = new AbortController().signal;
        args.providerContext = getProviderContext(providerKey);
        var target = mod[exportName];
        var value = typeof target === 'function' ? await target(args) : target;
        var json;
        try {
          json = JSON.stringify({ ok: true, value: value === undefined ? null : value });
        } catch (e) {
          json = JSON.stringify({ ok: false, error: 'Provider result is not serializable: ' + errorMessage(e) });
        }
        if (nativeRpc) nativeRpc.result(callId, json);
      } catch (e) {
        if (nativeRpc) {
          nativeRpc.result(callId, JSON.stringify({ ok: false, error: errorMessage(e) }));
        }
      } finally {
        activeProviderKey = '';
      }
    })();
    return 'started';
  }

  window.__harness = {
    loadModule: loadModule,
    callProvider: callProvider
  };

  if (nativeRpc) {
    try { nativeRpc.ready(); } catch (e) { /* bridge not ready yet */ }
  }
})();
