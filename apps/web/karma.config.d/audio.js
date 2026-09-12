// Let WebSeamsTest's read-aloud test actually PLAY.
//
// `WebTts.playAudioChunk` suspends until the AudioBufferSourceNode's `onended`. Under Chrome's
// default autoplay policy a context created without a user gesture stays `suspended` forever in an
// automated run — nothing plays, nothing ends, and the test times out after 2 s. The real app is
// fine (the context is created inside the tap that starts read-aloud); the test has no gesture to
// borrow, so the browser is told not to require one. `--mute-audio` keeps a CI machine silent.
// Leading and trailing `;`: karma.config.d snippets are concatenated verbatim, and an IIFE that
// follows another one without a separator is parsed as a CALL of its result.
;(function () {
  config.customLaunchers = Object.assign({}, config.customLaunchers, {
    ChromeHeadlessAudio: {
      base: "ChromeHeadless",
      flags: ["--autoplay-policy=no-user-gesture-required", "--mute-audio"],
    },
  })
  config.browsers = (config.browsers || []).map(function (b) {
    return b === "ChromeHeadless" ? "ChromeHeadlessAudio" : b
  })
})();
