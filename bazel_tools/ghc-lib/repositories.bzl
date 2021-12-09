load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "patch", "workspace_and_buildfile")

GHC_LIB_REPO_URL = "https://github.com/digital-asset/ghc-lib"
GHC_LIB_REV = "362d4f38a7ac10521393de9b7ad942a77a2605be"
GHC_LIB_SHA256 = "ce6ddf6b4706f811455cbe591f442d7b6519937d9ec1318f8544068609981986"

GHC_REPO_URL = "https://github.com/digital-asset/ghc"
GHC_REV = "9c787d4d24f2b515934c8503ee2bbd7cfac4da20"
GHC_SHA256 = "6f5f15eeea45e2b460f88d64fc59a997fed2597846feda179e8d1668c66d8645"
GHC_PATCHES_REV = [
    "ef89e28fd2efd7b37b3a8aa8b10512abf1926cde",
    "833ca63be2ab14871874ccb6974921e8952802e9",
]
GHC_PATCHES_SHA256 = [
    "85ce0f6a0902c888cbcafcc6921934922151e959c8c05d2078d1495508d1fb4f",
    "1b91aea4389d7d832b173c9e16bb65dab2112305415c17793da9021fb92740cd",
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
    # import ghc patches
    for (patch_rev, patch_sha256) in zip(GHC_PATCHES_REV, GHC_PATCHES_SHA256):
        repository_ctx.download(
            url = "{}/commit/{}.patch".format(GHC_REPO_URL, patch_rev),
            sha256 = patch_sha256,
            output = "patches/{}.patch".format(patch_rev),
        )

    #
    # patch ghc sources
    patch(
        repository_ctx,
        patches = [
            "patches/{}.patch".format(patch_rev)
            for patch_rev in GHC_PATCHES_REV
        ],
        patch_args = [
            "--strip=1",
            "--directory=ghc",
        ],
    )

    #
    # write WORKSPACE and BUILD
    workspace_and_buildfile(repository_ctx)

ghc_lib = repository_rule(
    _ghc_lib_impl,
    attrs = {
        "build_file": attr.label(allow_single_file = True),
        "build_file_content": attr.string(),
        "workspace_file": attr.label(allow_single_file = True),
        "workspace_file_content": attr.string(),
    },
)
