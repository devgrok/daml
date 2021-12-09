load("@rules_haskell//haskell:defs.bzl", "haskell_binary")
load("@rules_haskell//haskell:cabal.bzl", "haskell_cabal_library", "haskell_cabal_binary")

GHC_FLAVOR = "da-ghc-8.8.1"
GHC_LIB_VERSION = "8.8.1"

def ghc_lib():
    native.filegroup(
        name = "ghc-lib-gen-srcs",
        srcs = native.glob(["ghc-lib-gen/**"]) + [
            "LICENSE",
            "ghc-lib-gen.cabal",
        ],
    )
    native.filegroup(
        name = "ghc-srcs",
        srcs = native.glob(["ghc/**"]),
    )
    haskell_binary(
        name = "CI",
        srcs = ["CI.hs"],
        deps = [
            "@stackage//:base",
            "@stackage//:directory",
            "@stackage//:extra",
            "@stackage//:filepath",
            "@stackage//:optparse-applicative",
            "@stackage//:time",
        ],
    )
    haskell_cabal_library(
        name = "ghc-lib-gen-lib",
        package_name = "ghc-lib-gen",
        version = "0.1.0.0",
        haddock = False,
        srcs = [":ghc-lib-gen-srcs"],
        deps = [
            "@stackage//:base",
            "@stackage//:process",
            "@stackage//:filepath",
            "@stackage//:containers",
            "@stackage//:directory",
            "@stackage//:optparse-applicative",
            "@stackage//:bytestring",
            "@stackage//:yaml",
            "@stackage//:aeson",
            "@stackage//:text",
            "@stackage//:unordered-containers",
            "@stackage//:extra",
        ],
    )
    haskell_cabal_binary(
        name = "ghc-lib-gen",
        srcs = [":ghc-lib-gen-srcs"],
        deps = [
            ":ghc-lib-gen-lib",
            "@stackage//:base",
            "@stackage//:containers",
            "@stackage//:directory",
            "@stackage//:extra",
            "@stackage//:filepath",
            "@stackage//:optparse-applicative",
            "@stackage//:process",
        ],
    )
    native.genrule(
        name = "ghc-lib-parser",
        srcs = [":ghc-srcs"],
        tools = [":ghc-lib-gen"],
        outs = ["MISSING"],
        cmd = """\
echo "!!! PWD $$PWD"
echo "!!! ghc-lib-gen $(execpath :ghc-lib-gen)"
exit 1
""",
    )

def ghc_lib_gen():
    native.filegroup(
        name = "srcs",
        srcs = native.glob(["**"]),
        visibility = ["//visibility:public"],
    )
    haskell_cabal_library(
        name = "ghc-lib-gen-lib",
        package_name = "ghc-lib-gen",
        version = "0.1.0.0",
        haddock = False,
        srcs = [":srcs"],
        deps = [
            "@stackage//:base",
            "@stackage//:process",
            "@stackage//:filepath",
            "@stackage//:containers",
            "@stackage//:directory",
            "@stackage//:optparse-applicative",
            "@stackage//:bytestring",
            "@stackage//:yaml",
            "@stackage//:aeson",
            "@stackage//:text",
            "@stackage//:unordered-containers",
            "@stackage//:extra",
        ],
    )
    haskell_cabal_binary(
        name = "ghc-lib-gen",
        srcs = [":srcs"],
        deps = [
            ":ghc-lib-gen-lib",
            "@stackage//:base",
            "@stackage//:containers",
            "@stackage//:directory",
            "@stackage//:extra",
            "@stackage//:filepath",
            "@stackage//:optparse-applicative",
            "@stackage//:process",
        ],
        visibility = ["//visibility:public"],
    )

def ghc():
    native.filegroup(
        name = "srcs",
        srcs = native.glob(["**"]),
        visibility = ["//visibility:public"],
    )
    native.genrule(
        name = "ghc-lib-parser",
        srcs = [
            ":srcs",
            ":README.md",
        ],
        tools = [
            "@autoconf//:bin",
            "@autoconf//:bin/autoconf",
            "@automake//:bin",
            "@automake//:bin/automake",
            "@cabal-install//:bin/cabal",
            "@ghc865//:bin",
            "@ghc865//:bin/ghc",
            "@ghc-lib-gen",
            "@git//:bin/git",
            "@gnumake//:bin/make",
            "@stackage-exe//alex",
            "@stackage-exe//happy",
            "@stack//:bin/stack",
            "@xz//:bin/xz",
        ],
        toolchains = [
            "@rules_cc//cc:current_cc_toolchain",
        ],
        outs = [
            "ghc-lib-parser.cabal",
            "ghc-lib-parser-{}.tar.gz".format(GHC_LIB_VERSION),
        ],
        cmd = """\
set -euo pipefail
echo "!!! PWD $$PWD"
echo "!!! alex $(execpath @stackage-exe//alex)"
echo "!!! ghc $(execpath @ghc865//:bin/ghc)"
echo "!!! happy $(execpath @stackage-exe//happy)"
echo "!!! autoconf $(execpath @autoconf//:bin/autoconf)"
echo "!!! automake $(execpath @automake//:bin/automake)"
echo "!!! cabal-install $(execpath @cabal-install//:bin/cabal)"
echo "!!! stack $(execpath @stack//:bin/stack)"
echo "!!! ghc-lib-gen $(execpath @ghc-lib-gen)"

export LANG=C.UTF-8

get_path() {{ echo "$$(realpath "$$(dirname "$$1")")"; }}
alex_path="$$(get_path $(execpath @stackage-exe//alex))"
autoconf_path="$$(get_path $(execpath @autoconf//:bin/autoconf))"
automake_path="$$(get_path $(execpath @automake//:bin/automake))"
cabal_path="$$(get_path $(execpath @cabal-install//:bin/cabal))"
cc_path="$$(get_path $(CC))"
ghc_path="$$(get_path $(execpath @ghc865//:bin/ghc))"
git_path="$$(get_path $(execpath @git//:bin/git))"
gnumake_path="$$(get_path $(execpath @gnumake//:bin/make))"
happy_path="$$(get_path $(execpath @stackage-exe//happy))"
stack_path="$$(get_path $(execpath @stack//:bin/stack))"
xz_path="$$(get_path $(execpath @xz//:bin/xz))"
export PATH="$$alex_path:$$happy_path:$$autoconf_path:$$automake_path:$$cabal_path:$$cc_path:$$ghc_path:$$git_path:$$gnumake_path:$$stack_path:$$xz_path:$$PATH"

echo "!!! PATH $$PATH"

export CC="$$(realpath $(CC))"

GHC="$$(get_path $(execpath :README.md))"
TMP=$$(mktemp -d)
#trap "rm -rf $$TMP" EXIT
echo "!!! TMP $$TMP"
cp -rLt $$TMP $$GHC/.

export STACK_ROOT="$$TMP/.stack"
mkdir -p $$STACK_ROOT
echo -e "system-ghc: true\\ninstall-ghc: false" > $$STACK_ROOT/config.yaml

$(execpath @ghc-lib-gen) $$TMP --ghc-lib-parser --ghc-flavor={ghc_flavor}
sed -i.bak \\
  -e 's#version: 0.1.0#version: {ghc_lib_version}#' \\
  $$TMP/ghc-lib-parser.cabal
cp $$TMP/ghc-lib-parser.cabal $(execpath ghc-lib-parser.cabal)
(cd $$TMP; cabal sdist -o $$PWD/$(execpath ghc-lib-parser-{ghc_lib_version}.tar.gz))
""".format(
            ghc_flavor = GHC_FLAVOR,
            ghc_lib_version = GHC_LIB_VERSION,
        ),
    )
