# Invoked POST_BUILD by src/main/cpp/CMakeLists.txt for the hexagon flavor:
#   cmake -DSEARCH_DIR=<build tree> -DDST_DIR=<jniLibs/arm64-v8a> -DSKEL=libggml-htp-v73.so -P copy_htp_skel.cmake
# The skel is produced inside the ExternalProject's build tree at a path we do
# not want to hardcode (CMake changes its mind about that layout more often
# than we change releases), so glob for it and take the first hit; zero hits is
# fatal, because shipping without the skel reproduces the exact failure this
# whole branch exists to avoid.
file(GLOB_RECURSE skel_matches "${SEARCH_DIR}/**/${SKEL}")
list(REMOVE_DUPLICATES skel_matches)
list(LENGTH skel_matches skel_count)
if(skel_count LESS 1)
    message(FATAL_ERROR "htp skel '${SKEL}' not found anywhere under ${SEARCH_DIR}")
endif()
list(GET skel_matches 0 skel_src)
file(MAKE_DIRECTORY "${DST_DIR}")
execute_process(COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${skel_src}" "${DST_DIR}/${SKEL}"
    RESULT_VARIABLE skel_rc)
if(NOT skel_rc EQUAL 0)
    message(FATAL_ERROR "failed to copy htp skel ${skel_src} -> ${DST_DIR} (rc=${skel_rc})")
endif()
message(STATUS "sdcpp: htp skel packaged: ${skel_src} -> ${DST_DIR}/${SKEL}")
