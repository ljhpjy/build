//go:build android && cgo

package pow

/*
#include <stdlib.h>
*/
import "C"

// 引用一下，确保这个文件确实把包带入 cgo 构建。
var _ = C.malloc

// 仅用于在 CGO_ENABLED=1 时强制走 cgo 外部链接（NDK clang）。
// 这样 android/amd64 的 PIE 才能编译，arm64 也能得到动态 PIE + 16KB 对齐的备选版本。
