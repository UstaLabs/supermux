window.__appLoaded = true;
fetch('data.json').then(function (r) { return r.text(); }).then(function (t) { window.__fetched = t; });
