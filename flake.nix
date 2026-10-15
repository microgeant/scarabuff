{
  description = "scarabuff dev environment: flatc pinned to FlatBuffers v25.2.10 (matching gradle/libs.versions.toml's flatbuffers-java/flatbuffers-java-grpc versions), plus JDK 17 and Gradle.";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.05";

  outputs = { self, nixpkgs }:
    let
      flatcVersion = "25.2.10";

      # Official prebuilt flatc binaries from the FlatBuffers v${flatcVersion} GitHub release
      # (https://github.com/google/flatbuffers/releases/tag/v25.2.10). sha256 is of the exact
      # release asset bytes, verified by hand (`shasum -a 256`) against the published asset.
      #
      # Mac.flatc.binary.zip is a universal (x86_64 + arm64) binary, so it covers both Darwin
      # systems below. There is no official aarch64-linux asset for this release, so that system
      # is intentionally not listed — `nix develop` on it fails loudly rather than silently
      # falling back to an unpinned/wrong-arch flatc.
      flatcAssets = {
        x86_64-linux = {
          url = "https://github.com/google/flatbuffers/releases/download/v${flatcVersion}/Linux.flatc.binary.g++-13.zip";
          sha256 = "6f01258d7475806f375d6da66a61df47add8016edd73f1774673f37b80b9a711";
        };
        x86_64-darwin = {
          url = "https://github.com/google/flatbuffers/releases/download/v${flatcVersion}/Mac.flatc.binary.zip";
          sha256 = "eabd8e8c4d8b9381b7ea4c6324c629f9200d8bb73f94b6b28ef406efc8c06c62";
        };
        aarch64-darwin = {
          url = "https://github.com/google/flatbuffers/releases/download/v${flatcVersion}/Mac.flatc.binary.zip";
          sha256 = "eabd8e8c4d8b9381b7ea4c6324c629f9200d8bb73f94b6b28ef406efc8c06c62";
        };
      };

      forEachSupportedSystem = f: nixpkgs.lib.genAttrs (builtins.attrNames flatcAssets) (system: f system);
    in
    {
      packages = forEachSupportedSystem (system:
        let
          pkgs = import nixpkgs { inherit system; };
          asset = flatcAssets.${system};
          flatc = pkgs.stdenvNoCC.mkDerivation {
            pname = "flatc";
            version = flatcVersion;
            src = pkgs.fetchurl { inherit (asset) url sha256; };
            nativeBuildInputs = [ pkgs.unzip ];
            sourceRoot = ".";
            installPhase = ''
              runHook preInstall
              mkdir -p $out/bin
              install -m755 flatc $out/bin/flatc
              runHook postInstall
            '';
          };
        in
        {
          inherit flatc;
          default = flatc;
        });

      devShells = forEachSupportedSystem (system:
        let
          pkgs = import nixpkgs { inherit system; };
          flatc = self.packages.${system}.flatc;
        in
        {
          default = pkgs.mkShell {
            packages = [ flatc pkgs.jdk17 pkgs.gradle ];

            # ScarabuffExtension.flatc defaults to $FLATC, so inside this shell the plugin uses
            # this pinned flatc instead of downloading its own (the same version either way).
            shellHook = ''
              export FLATC=${flatc}/bin/flatc
              echo "scarabuff devShell: $($FLATC --version), $(java -version 2>&1 | head -1)"
            '';
          };
        });
    };
}
