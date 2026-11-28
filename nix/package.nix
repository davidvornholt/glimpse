# glimpse, compiled the same way as `bun run install:user`, with its agent skill
# in share/glimpse/skills/<skill>. passthru.skills names it.
{
  bun,
  fetchurl,
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
  # The registry packages bun.lock records, each fetched by the integrity hash
  # the lockfile holds, so a change to bun.lock needs no other edit here.
  # Nix's JSON reader rejects the trailing commas Bun writes, so they go first;
  # no string in bun.lock holds a comma before a closing bracket.
  lock = builtins.fromJSON (
    lib.concatMapStrings (part: if builtins.isList part then builtins.head part else part) (
      builtins.split ",[[:space:]]*([]}])" (builtins.readFile ../bun.lock)
    )
  );
  # An os or cpu field lists the values a package installs on, or, prefixed
  # with "!", the ones it doesn't. Packages for other platforms aren't fetched.
  allows =
    field: value:
    let
      values = lib.toList field;
    in
    values == [ ]
    || lib.elem value values
    || (lib.all (lib.hasPrefix "!") values && !lib.elem "!${value}" values);
  cpu =
    {
      x86_64 = "x64";
      aarch64 = "arm64";
    }
    .${stdenvNoCC.hostPlatform.parsed.cpu.name};
  # The tarball path Bun requests from a registry for each package, and the
  # fetched tarball it gets there.
  tarballs = lib.listToAttrs (
    lib.concatMap (
      entry:
      let
        parsed = builtins.match "(@?[^@]+)@(.*)" (builtins.head entry);
        name = builtins.elemAt parsed 0;
        version = builtins.elemAt parsed 1;
        info = builtins.elemAt entry 2;
        tarball = "/${name}/-/${baseNameOf name}-${version}.tgz";
      in
      if lib.hasPrefix "workspace:" version then
        [ ]
      else if builtins.length entry != 4 || builtins.elemAt entry 1 != "" then
        throw "nix/package.nix can only install packages from the default npm registry, not ${builtins.head entry} from bun.lock"
      else
        lib.optional (allows (info.os or [ ]) "linux" && allows (info.cpu or [ ]) cpu) (
          lib.nameValuePair tarball (fetchurl {
            url = "https://registry.npmjs.org${tarball}";
            hash = builtins.elemAt entry 3;
          })
        )
    ) (builtins.attrValues lock.packages)
  );
  # The CLI's runtime dependencies only; the compiled binary bundles them.
  # Bun installs them without a network from a registry on the build's
  # loopback that serves the fetched tarballs. Filling Bun's install cache
  # directly would need its folder names, which hold an internal hash of a
  # prerelease version.
  nodeModules = stdenvNoCC.mkDerivation {
    name = "${pname}-node-modules";
    inherit src;
    nativeBuildInputs = [ bun ];
    dontConfigure = true;
    tarballs = builtins.toJSON tarballs;
    passAsFile = [ "tarballs" ];
    buildPhase = ''
      runHook preBuild
      export HOME=$TMPDIR BUN_INSTALL_CACHE_DIR=$TMPDIR/bun-cache
      bun -e "
        const [tarballs, portFile] = process.argv.slice(-2);
        const files = await Bun.file(tarballs).json();
        const server = Bun.serve({
          hostname: '127.0.0.1',
          port: 0,
          fetch(request) {
            const path = decodeURIComponent(new URL(request.url).pathname);
            if (Object.hasOwn(files, path)) return new Response(Bun.file(files[path]));
            console.error('The Nix build has no package at ' + path);
            return new Response(null, { status: 404 });
          },
        });
        await Bun.write(portFile, String(server.port));
      " "$tarballsPath" "$TMPDIR/registry-port" &
      registry=$!
      while [ ! -s "$TMPDIR/registry-port" ]; do
        kill -0 "$registry"
        sleep 0.1
      done
      BUN_CONFIG_REGISTRY="http://127.0.0.1:$(cat "$TMPDIR/registry-port")/" \
        bun install --frozen-lockfile --production --ignore-scripts --no-progress
      kill "$registry"
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      mkdir "$out"
      find . -type d -name node_modules -prune -print0 | tar --null --files-from=- -cf - | tar -C "$out" -xf -
      runHook postInstall
    '';
    dontFixup = true;
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
