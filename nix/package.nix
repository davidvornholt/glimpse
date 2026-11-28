# glimpse, compiled the same way as `bun run install:user`, with its agent skill
# in share/glimpse/skills/<skill>. passthru.skills names it.
{
  bun,
  callPackage,
  lib,
  stdenvNoCC,
  version,
}:
let
  pname = "glimpse";
  # The files git doesn't ignore; package-source.nix says what that protects.
  src = import ./package-source.nix { inherit lib; } ../.;
  skills = builtins.attrNames (
    lib.filterAttrs (_name: type: type == "directory") (builtins.readDir ../apps/cli/skills)
  );
  # The workspaces' production dependencies; the compiled CLI bundles the ones
  # it uses. bun-lock-deps.nix fetches them by the hashes in bun.lock.
  nodeModules = callPackage ./bun-lock-deps.nix {
    inherit bun src;
    name = "${pname}-node-modules";
  };
in
stdenvNoCC.mkDerivation {
  inherit pname version src;
  nativeBuildInputs = [ bun ];
  dontConfigure = true;
  buildPhase = ''
    runHook preBuild
    export HOME=$TMPDIR
    cp -r --no-preserve=mode ${nodeModules}/. .
    cd apps/cli
    bun run build
    runHook postBuild
  '';
  installPhase = ''
    runHook preInstall
    install -Dm755 dist/glimpse "$out/bin/glimpse"
    mkdir -p "$out/share/${pname}"
    cp -r skills "$out/share/${pname}/skills"
    runHook postInstall
  '';
  # Bun appends the bundled program to its own executable; leave it intact.
  dontStrip = true;
  dontPatchELF = true;
  # The Standards workflow builds this package, so a binary that can't start,
  # or skills installed differently from apps/cli/skills, fail `check`. The
  # comparison reads the skills unfiltered, so it also catches a tracked skill
  # file that a .gitignore line keeps out of src.
  doInstallCheck = true;
  installCheckPhase = ''
    runHook preInstallCheck
    HOME=$TMPDIR $out/bin/glimpse --help >/dev/null
    diff -r ${../apps/cli/skills} "$out/share/${pname}/skills"
    runHook postInstallCheck
  '';

  passthru = { inherit nodeModules skills; };
  meta = {
    description = "Command-line tool for agents to wait for and look at the photos you take with the Glimpse app";
    homepage = "https://github.com/davidvornholt/glimpse";
    license = lib.licenses.mit;
    mainProgram = "glimpse";
    platforms = lib.platforms.linux;
  };
}
