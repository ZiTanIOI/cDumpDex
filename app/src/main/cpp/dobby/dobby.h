// Dobby Hook Framework Header
// https://github.com/jmpews/Dobby

#ifndef DOBBY_H_
#define DOBBY_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Dobby hook function
// @param target_func: 目标函数地址
// @param replace_func: 替换函数地址
// @param origin_func: 原始函数指针的存储地址（用于调用原始函数）
// @return: 0 表示成功，其他表示错误
int DobbyHook(void *target_func, void *replace_func, void **origin_func);

// Dobby destroy hook
// @param target_func: 目标函数地址
// @return: 0 表示成功，其他表示错误
int DobbyDestroy(void *target_func);

// Dobby get symbol address from library
// @param image_name: 库名称（如 "libart.so"）
// @param symbol_name: 符号名称
// @return: 符号地址，失败返回 NULL
void *DobbySymbolResolver(const char *image_name, const char *symbol_name);

// Dobby get symbol address from file
// @param file_path: 文件路径
// @param symbol_name: 符号名称
// @return: 符号地址，失败返回 NULL
void *DobbyGetSymbol(const char *file_path, const char *symbol_name);

#ifdef __cplusplus
}
#endif

#endif // DOBBY_H_
