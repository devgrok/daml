load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "patch", "workspace_and_buildfile")

GHC_LIB_REPO_URL = "https://github.com/digital-asset/ghc-lib"
GHC_LIB_REV = "362d4f38a7ac10521393de9b7ad942a77a2605be"
GHC_LIB_SHA256 = "ce6ddf6b4706f811455cbe591f442d7b6519937d9ec1318f8544068609981986"

GHC_REPO_URL = "https://github.com/digital-asset/ghc"
GHC_REV = "3d554575dc40375a1e4995def35cca17a3e9aa95"
GHC_SHA256 = "26891fb947ed928d3b515a827060ead1a677a4eb8313d29ab57cdf9d481af04b"
GHC_PATCHES = [
    Label("@//bazel_tools/ghc-lib:ghc-daml-prim.patch"),
]
GHC_FLAVOR = "da-ghc-8.8.1"

def _ghc_lib_impl(repository_ctx):
    #
    # import ghc-lib repository
    repository_ctx.download_and_extract(
        url = "{}/archive/{}.tar.gz".format(GHC_LIB_REPO_URL, GHC_LIB_REV),
        sha256 = GHC_LIB_SHA256,
        stripPrefix = "ghc-lib-{}".format(GHC_LIB_REV),
    )

    #
    # import ghc repository
    repository_ctx.download_and_extract(
        url = "{}/archive/{}.tar.gz".format(GHC_REPO_URL, GHC_REV),
        sha256 = GHC_SHA256,
        stripPrefix = "ghc-{}".format(GHC_REV),
        output = "ghc",
    )

    #
    # patch ghc sources
    patch(
        repository_ctx,
        patches = [
            repository_ctx.path(patch)
            for patch in GHC_PATCHES
        ],
        patch_args = [
            "--strip=1",
            "--directory=ghc",
        ],
    )

    #
    # write WORKSPACE and BUILD
    repository_ctx.template(
        "BUILD.bazel",
        repository_ctx.path(Label("@//bazel_tools/ghc-lib:BUILD.ghc-lib")),
    )

ghc_lib = repository_rule(
    _ghc_lib_impl,
    attrs = {
    },
)
