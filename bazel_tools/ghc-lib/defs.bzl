load("@rules_haskell//haskell:defs.bzl", "haskell_binary")
load("@rules_haskell//haskell:cabal.bzl", "haskell_cabal_library", "haskell_cabal_binary")

GHC_FLAVOR = "da-ghc-8.8.1"

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
