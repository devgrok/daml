load("@bazel_tools//tools/build_defs/repo:git.bzl", "new_git_repository")
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "patch", "workspace_and_buildfile")

GHC_LIB_REPO_URL = "https://github.com/digital-asset/ghc-lib"
GHC_LIB_REV = "362d4f38a7ac10521393de9b7ad942a77a2605be"
GHC_LIB_SHA256 = "ce6ddf6b4706f811455cbe591f442d7b6519937d9ec1318f8544068609981986"

GHC_REPO_URL = "https://github.com/digital-asset/ghc"
GHC_REV = "3d554575dc40375a1e4995def35cca17a3e9aa95"
GHC_SHA256 = "26891fb947ed928d3b515a827060ead1a677a4eb8313d29ab57cdf9d481af04b"
GHC_PATCHES = [
    "@//bazel_tools/ghc-lib:ghc-daml-prim.patch",
]

def ghc_lib(*args, **kwargs):
    http_archive(
        name = "ghc-lib-gen",
        url = "{}/archive/{}.tar.gz".format(GHC_LIB_REPO_URL, GHC_LIB_REV),
        sha256 = GHC_LIB_SHA256,
        strip_prefix = "ghc-lib-{}".format(GHC_LIB_REV),
        build_file = "@//bazel_tools/ghc-lib:BUILD.ghc-lib-gen",
    )
    new_git_repository(
        name = "da-ghc",
        remote = GHC_REPO_URL,
        commit = GHC_REV,
        recursive_init_submodules = True,
        build_file = "@//bazel_tools/ghc-lib:BUILD.ghc",
        shallow_since = "1639050525 +0100",
        patches = GHC_PATCHES,
        patch_args = ["-p1"],
    )
