load("@rules_haskell//haskell:defs.bzl", "haskell_binary")

GHC_FLAVOR = "da-ghc-8.8.1"

def ghc_lib():
    native.filegroup(
        name = "srcs",
        srcs = native.glob(["**"], exclude = ["CI.hs"]),
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
