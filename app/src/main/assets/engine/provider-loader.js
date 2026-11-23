// provider-loader.js — StreamOne provider engine glue.
//
// Evaluated FIRST inside every per-provider QuickJS context, before
// axios-shim.js and cheerio.bundle.js. ProviderEngine (Kotlin) pre-defines
// three bridge objects through the QuickJS binding:
//
//   __nativeHttp : { http(requestJson: string): string }   (OkHttp, synchronous)
//   __nativeKv   : { get(k), set(k, v), delete(k), keys(), clear() }  (strings)
//   __nativeLog  : { log(message: string) }
//
// This file turns them into the environment Vega provider bundles expect:
// a callable __httpRequest(json), console, timers, atob/btoa, URL,
// URLSearchParams, TextEncoder/TextDecoder, a minimal fetch(), the
// providerContext object, a CommonJS module registry for dist bundles and
// the __callProvider / __pollSlot entry points used from Kotlin.
//
// Dist bundles are esbuild CommonJS output: they assign to `exports.*`
// (and sometimes `module.exports`) and never call require() for externals —
// everything they need arrives via the providerContext argument.

(function () {
  'use strict';

  // ---------------------------------------------------------------- bridges
  var httpBridge = globalThis.__nativeHttp;
  var kvBridge = globalThis.__nativeKv;
  var logBridge = globalThis.__nativeLog;

  // The axios shim calls this plain function; it forwards to the bridge
  // object. (A dedicated name is used instead of reassigning __nativeHttp:
  // properties defined through the native binding may be read-only.)
  globalThis.__httpRequest = function (requestJson) {
    return httpBridge.http(requestJson);
  };

  function logLine(args) {
    try {
      var parts = [];
      for (var i = 0; i < args.length; i++) {
        var a = args[i];
        if (typeof a === 'string') parts.push(a);
        else {
          try { parts.push(JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
        }
      }
      if (logBridge && typeof logBridge.log === 'function') {
        logBridge.log(parts.join(' '));
      }
    } catch (e) { /* logging must never break a provider */ }
  }

  globalThis.console = {
    log: function () { logLine(arguments); },
    info: function () { logLine(arguments); },
    warn: function () { logLine(arguments); },
    error: function () { logLine(arguments); },
    debug: function () { logLine(arguments); }
  };

  // ---------------------------------------------------------------- timers
  // QuickJS has no event loop timers. Provider code occasionally defers work
  // with setTimeout; callbacks are queued and executed by the Kotlin poll
  // loop via __runTimers(). Delays are not honoured precisely — callbacks
  // run as soon as the current call stack unwinds.
  var timers = [];
  var timerSeq = 1;
  globalThis.setTimeout = function (fn, _ms) {
    var id = timerSeq++;
    var args = [];
    for (var i = 2; i < arguments.length; i++) args.push(arguments[i]);
    timers.push({ id: id, fn: fn, args: args });
    return id;
  };
  globalThis.setInterval = globalThis.setTimeout; // one-shot safety net
  globalThis.clearTimeout = function (id) {
    timers = timers.filter(function (t) { return t.id !== id; });
  };
  globalThis.clearInterval = globalThis.clearTimeout;
  globalThis.__runTimers = function () {
    var pending = timers;
    timers = [];
    pending.forEach(function (t) {
      try { if (typeof t.fn === 'function') t.fn.apply(null, t.args); } catch (e) { logLine(['timer error', String(e)]); }
    });
    return '';
  };

  // ---------------------------------------------------------------- atob/btoa
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  if (typeof globalThis.atob !== 'function') {
    globalThis.atob = function (input) {
      var str = String(input).replace(/=+$/, '');
      var output = '';
      var bc = 0, bs = 0, buffer, idx = 0;
      while ((buffer = str.charAt(idx++))) {
        buffer = B64.indexOf(buffer);
        if (buffer < 0) continue;
        bs = bc % 4 ? bs * 64 + buffer : buffer;
        if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
      }
      return output;
    };
  }
  if (typeof globalThis.btoa !== 'function') {
    globalThis.btoa = function (input) {
      var str = String(input);
      var output = '';
      for (var block = 0, charCode, i = 0, map = B64;
           str.charAt(i | 0) || (map = '=', i % 1);
           output += map.charAt(63 & (block >> (8 - (i % 1) * 8)))) {
        charCode = str.charCodeAt(i += 3 / 4);
        if (charCode > 0xFF) throw new Error('btoa: character out of range');
        block = (block << 8) | charCode;
      }
      return output;
    };
  }

  // ---------------------------------------------------------------- URLSearchParams
  if (typeof globalThis.URLSearchParams !== 'function') {
    var USP = function (init) {
      this._entries = [];
      if (!init) return;
      if (typeof init === 'string') {
        var query = init.charAt(0) === '?' ? init.slice(1) : init;
        if (!query) return;
        var self = this;
        query.split('&').forEach(function (pair) {
          if (!pair) return;
          var eq = pair.indexOf('=');
          var k = eq >= 0 ? pair.slice(0, eq) : pair;
          var v = eq >= 0 ? pair.slice(eq + 1) : '';
          self._entries.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
        });
      } else if (Array.isArray(init)) {
        var self2 = this;
        init.forEach(function (pair) { self2._entries.push([String(pair[0]), String(pair[1])]); });
      } else if (typeof init === 'object') {
        var self3 = this;
        Object.keys(init).forEach(function (k) { self3._entries.push([k, String(init[k])]); });
      }
    };
    USP.prototype.append = function (k, v) { this._entries.push([String(k), String(v)]); };
    USP.prototype.set = function (k, v) {
      this.delete(k);
      this._entries.push([String(k), String(v)]);
    };
    USP.prototype.get = function (k) {
      for (var i = 0; i < this._entries.length; i++) {
        if (this._entries[i][0] === k) return this._entries[i][1];
      }
      return null;
    };
    USP.prototype.getAll = function (k) {
      return this._entries.filter(function (e) { return e[0] === k; })
        .map(function (e) { return e[1]; });
    };
    USP.prototype.has = function (k) { return this.get(k) !== null; };
    USP.prototype.delete = function (k) {
      this._entries = this._entries.filter(function (e) { return e[0] !== k; });
    };
    USP.prototype.forEach = function (cb) {
      var self = this;
      this._entries.forEach(function (e) { cb.call(self, e[1], e[0], self); });
    };
    USP.prototype.toString = function () {
      return this._entries.map(function (e) {
        return encodeURIComponent(e[0]) + '=' + encodeURIComponent(e[1]);
      }).join('&');
    };
    globalThis.URLSearchParams = USP;
  }

  // ---------------------------------------------------------------- URL (minimal)
  if (typeof globalThis.URL !== 'function') {
    var UrlPolyfill = function (url, base) {
      var input = String(url);
      if (base !== undefined && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(input)) {
        var b = new UrlPolyfill(String(base));
        if (input.indexOf('//') === 0) {
          input = b.protocol + input;
        } else if (input.charAt(0) === '/') {
          input = b.origin + input;
        } else if (input.charAt(0) === '?') {
          input = b.origin + b.pathname + input;
        } else if (input.charAt(0) === '#') {
          input = b.origin + b.pathname + b.search + input;
        } else {
          var dir = b.pathname.replace(/[^/]*$/, '');
          input = b.origin + dir + input;
        }
      }
      var m = /^((?:[a-zA-Z][a-zA-Z0-9+.-]*:)?\/\/)?([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(input);
      if (!m) throw new Error('Invalid URL: ' + url);
      var schemePart = m[1] || '';
      this.protocol = schemePart.replace(/\/\/$/, '');
      var authority = m[2] || '';
      var atIdx = authority.lastIndexOf('@');
      var hostPort = atIdx >= 0 ? authority.slice(atIdx + 1) : authority;
      var colonIdx = hostPort.lastIndexOf(':');
      if (colonIdx >= 0 && /^\d+$/.test(hostPort.slice(colonIdx + 1))) {
        this.hostname = hostPort.slice(0, colonIdx);
        this.port = hostPort.slice(colonIdx + 1);
      } else {
        this.hostname = hostPort;
        this.port = '';
      }
      this.host = this.port ? this.hostname + ':' + this.port : this.hostname;
      this.pathname = m[3] || (this.hostname ? '/' : '');
      this.search = m[4] || '';
      this.hash = m[5] || '';
      this.origin = this.protocol && this.hostname ? this.protocol + '//' + this.host : 'null';
      this.searchParams = new globalThis.URLSearchParams(this.search);
      var self = this;
      Object.defineProperty(this, 'href', {
        get: function () {
          return (self.protocol ? self.protocol + '//' : '') + self.host + self.pathname + self.search + self.hash;
        },
        set: function (v) {
          var next = new UrlPolyfill(String(v));
          self.protocol = next.protocol; self.host = next.host; self.hostname = next.hostname;
          self.port = next.port; self.pathname = next.pathname; self.search = next.search;
          self.hash = next.hash; self.origin = next.origin; self.searchParams = next.searchParams;
        }
      });
    };
    UrlPolyfill.prototype.toString = function () { return this.href; };
    UrlPolyfill.createObjectURL = function () { throw new Error('URL.createObjectURL is not available'); };
    UrlPolyfill.revokeObjectURL = function () {};
    globalThis.URL = UrlPolyfill;
  }

  // ---------------------------------------------------------------- TextEncoder / TextDecoder (UTF-8)
  if (typeof globalThis.TextEncoder !== 'function') {
    globalThis.TextEncoder = function () {};
    globalThis.TextEncoder.prototype.encode = function (str) {
      var bytes = [];
      var s = String(str);
      for (var i = 0; i < s.length; i++) {
        var code = s.codePointAt(i);
        if (code > 0xFFFF) i++; // skip low surrogate
        if (code < 0x80) bytes.push(code);
        else if (code < 0x800) bytes.push(0xC0 | (code >> 6), 0x80 | (code & 0x3F));
        else if (code < 0x10000) bytes.push(0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F));
        else bytes.push(0xF0 | (code >> 18), 0x80 | ((code >> 12) & 0x3F), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F));
      }
      return new Uint8Array(bytes);
    };
  }
  if (typeof globalThis.TextDecoder !== 'function') {
    globalThis.TextDecoder = function () {};
    globalThis.TextDecoder.prototype.decode = function (input) {
      var bytes = input instanceof Uint8Array ? input : new Uint8Array(input || []);
      var out = '';
      var i = 0;
      while (i < bytes.length) {
        var b0 = bytes[i];
        var code;
        if (b0 < 0x80) { code = b0; i += 1; }
        else if ((b0 & 0xE0) === 0xC0) { code = ((b0 & 0x1F) << 6) | (bytes[i + 1] & 0x3F); i += 2; }
        else if ((b0 & 0xF0) === 0xE0) { code = ((b0 & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F); i += 3; }
        else if ((b0 & 0xF8) === 0xF0) { code = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F); i += 4; }
        else { code = 0xFFFD; i += 1; }
        out += String.fromCodePoint(code);
      }
      return out;
    };
  }

  // ---------------------------------------------------------------- fetch (minimal)
  if (typeof globalThis.fetch !== 'function') {
    globalThis.fetch = function (url, options) {
      options = options || {};
      var nativeRequest = {
        url: String(url),
        method: (options.method || 'GET').toUpperCase(),
        headers: options.headers || {},
        data: typeof options.body === 'string' ? options.body : null,
        timeout: 0,
        responseType: ''
      };
      return new Promise(function (resolve, reject) {
        var raw;
        try {
          raw = httpBridge.http(JSON.stringify(nativeRequest));
        } catch (e) {
          reject(new Error('fetch failed: ' + (e && e.message ? e.message : e)));
          return;
        }
        var resp = JSON.parse(raw);
        if (resp.error && !resp.status) {
          reject(new Error('fetch failed: ' + resp.error));
          return;
        }
        var headerMap = {};
        Object.keys(resp.headers || {}).forEach(function (k) {
          headerMap[k.toLowerCase()] = resp.headers[k];
        });
        resolve({
          ok: resp.status >= 200 && resp.status < 300,
          status: resp.status,
          statusText: resp.statusText || '',
          url: nativeRequest.url,
          headers: {
            get: function (name) {
              var v = headerMap[String(name).toLowerCase()];
              return v === undefined ? null : v;
            }
          },
          text: function () { return Promise.resolve(resp.body || ''); },
          json: function () {
            try { return Promise.resolve(JSON.parse(resp.body || '')); }
            catch (e) { return Promise.reject(e); }
          }
        });
      });
    };
  }

  // ---------------------------------------------------------------- provider context
  // Default headers, copied from the Vega template (providers/headers.ts).
  var COMMON_HEADERS = {
    'sec-ch-ua': '"Not_A Brand";v="8", "Chromium";v="120", "Microsoft Edge";v="120"',
    'sec-ch-ua-mobile': '?0',
    'sec-ch-ua-platform': '"Windows"',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0'
  };

  var cachedContext = null;
  function getProviderContext() {
    if (cachedContext) return cachedContext;
    cachedContext = {
      axios: globalThis.__axiosShim,
      commonHeaders: COMMON_HEADERS,
      cheerio: globalThis.cheerio,
      // STUB: the WAF-solving WebView is not implemented in this phase.
      // Providers that need openWebView (e.g. Cloudflare challenges) will
      // receive success:false, exactly like the template's local-test stub.
      openWebView: function (url, _options) {
        return Promise.resolve({
          success: false,
          data: '',
          cookies: '',
          cookieMap: {},
          userAgent: '',
          url: url
        });
      },
      kvStore: {
        get: function (key) {
          var raw = kvBridge.get(String(key));
          if (raw === '' || raw === null || raw === undefined) return Promise.resolve(undefined);
          try { return Promise.resolve(JSON.parse(raw)); }
          catch (e) { return Promise.resolve(undefined); }
        },
        set: function (key, value) {
          if (value === undefined) kvBridge.delete(String(key));
          else kvBridge.set(String(key), JSON.stringify(value));
          return Promise.resolve();
        },
        delete: function (key) {
          return Promise.resolve(kvBridge.delete(String(key)) === 'true');
        },
        keys: function () {
          try { return Promise.resolve(JSON.parse(kvBridge.keys())); }
          catch (e) { return Promise.resolve([]); }
        },
        clear: function () {
          kvBridge.clear();
          return Promise.resolve();
        }
      }
    };
    return cachedContext;
  }

  // ---------------------------------------------------------------- module registry
  var modules = {};

  globalThis.__loadModule = function (name, source) {
    var module = { exports: {} };
    var factory = new Function(
      'module', 'exports', 'require',
      source + '\n//# sourceURL=provider-' + name + '.js'
    );
    factory(module, module.exports, function (id) {
      throw new Error('require("' + id + '") is not available in the provider sandbox');
    });
    modules[name] = module.exports;
    return 'ok';
  };

  function errorToString(e) {
    if (!e) return 'unknown error';
    if (e.stack) return String(e.stack);
    if (e.message) return String(e.message);
    return String(e);
  }

  // Calls an exported provider function (or reads a plain exported value,
  // e.g. the `catalog` array) and stores the settled result in a slot that
  // Kotlin polls via __pollSlot(). Returns the slot JSON immediately when
  // the result is already available, otherwise the string 'pending'.
  globalThis.__callProvider = function (moduleName, exportName, argsJson) {
    globalThis.__slot = undefined;
    try {
      var mod = modules[moduleName];
      if (!mod) throw new Error('Provider module not loaded: ' + moduleName);
      var target = mod[exportName];
      var args = argsJson ? JSON.parse(argsJson) : {};
      args.providerContext = getProviderContext();
      var result = typeof target === 'function' ? target(args) : target;
      Promise.resolve(result).then(
        function (value) {
          globalThis.__slot = { ok: true, value: value === undefined ? null : value };
        },
        function (err) {
          globalThis.__slot = { ok: false, error: errorToString(err) };
        }
      );
    } catch (e) {
      globalThis.__slot = { ok: false, error: errorToString(e) };
    }
    return globalThis.__slot ? JSON.stringify(globalThis.__slot) : 'pending';
  };

  globalThis.__pollSlot = function () {
    return globalThis.__slot ? JSON.stringify(globalThis.__slot) : 'pending';
  };
})();
