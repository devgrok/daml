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
        srcs = [":srcs"],
        tools = [
            "@ghc-lib-gen",
            "@stackage-exe//happy",
        ],
        outs = ["MISSING"],
        cmd = """\
echo "!!! PWD $$PWD"
echo "!!! happy $(execpath @stackage-exe//happy)"
echo "!!! ghc-lib-gen $(execpath @ghc-lib-gen)"
exit 1

export LANG=C.UTF-8

local stack_path="$$(dirname $(execpath @stack//:bin/stack))"
local happy_path="$$(dirname $(execpath @stackage-exe//happy))"
local autoconf_path="$$(dirname $(execpath @autoconf//:bin/autoconf))"
local automake_path="$$(dirname $(execpath @automake//:bin/automake))"
export PATH="$$stack_path:$$happy_path:$$autoconf_path:$$automake_path:$PATH"

local GHC="$$(dirname $(execpath @da-ghc//:README.md))"

$(execpath @ghc-lib-gen) $$GHC --ghc-lib-parser --ghc-flavor={ghc_flavor}
sed -i.bak \\
  -e 's#version: 0.1.0#version: {ghc_lib_version}#' \\
  $$GHC/ghc-lib-parser.cabal
""",
    )
