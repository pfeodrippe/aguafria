(() => {
  const canvas = document.querySelector("#game");
  const error = document.querySelector("#error");

  createAguafriaGame({
    canvas,
    printErr: message => { error.textContent = String(message); }
  }).then(module => {
    module._web_start();
  }).catch(reason => {
    error.textContent = reason?.stack || String(reason);
  });
})();
