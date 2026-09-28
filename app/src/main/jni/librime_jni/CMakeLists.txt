find_package(Opencc REQUIRED)

set(RIME_JNI_SOURCES
  key_table.cc
  levers.cc
  opencc.cc
  rime_jni.cc
)
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})
# NDK r27 and lower default to 4 KB ELF segment alignment. Android 15+
# devices may use 16 KB pages, so make the final shared object page-size agnostic.
target_link_options(rime_jni PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)
target_link_libraries(rime_jni rime-static log ${Opencc_LIBRARY})
target_include_directories(rime_jni PRIVATE
  "${CMAKE_SOURCE_DIR}/librime/src"
  "${Opencc_INCLUDE_PATH}"
)

execute_process(
  COMMAND git --git-dir ${CMAKE_SOURCE_DIR}/../../../../.git describe --tags --always
  OUTPUT_VARIABLE TRIME_VERSION
)
string(STRIP ${TRIME_VERSION} TRIME_VERSION)

execute_process(
  COMMAND git --git-dir ${CMAKE_SOURCE_DIR}/librime/deps/opencc/.git describe --tags --always
  OUTPUT_VARIABLE OPENCC_VERSION
)
string(STRIP ${OPENCC_VERSION} OPENCC_VERSION)

execute_process(
  COMMAND git --git-dir ${CMAKE_SOURCE_DIR}/librime/.git describe --tags --always
  OUTPUT_VARIABLE LIBRIME_VERSION
)
string(STRIP ${LIBRIME_VERSION} LIBRIME_VERSION)

target_compile_definitions(rime_jni PRIVATE
  TRIME_VERSION="${TRIME_VERSION}"
  OPENCC_VERSION="${OPENCC_VERSION}"
  LIBRIME_VERSION="${LIBRIME_VERSION}"
)
