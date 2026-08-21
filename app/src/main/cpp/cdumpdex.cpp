#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <set>
#include <map>
#include <vector>
#include <string>
#include <sstream>
#include <iomanip>
#include <mutex>
#include <atomic>
#include <limits>
#include <cerrno>
#include <cstdarg>

#define LOG_TAG "cdumpdex"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#include "dobby/dobby.h"

// DEX Header 结构
struct DexHeader {
    uint8_t magic_[8];
    uint32_t checksum_;
    uint8_t signature_[20];
    uint32_t file_size_;
    uint32_t header_size_;
    uint32_t endian_tag_;
    uint32_t link_size_;
    uint32_t link_off_;
    uint32_t map_off_;
    uint32_t string_ids_size_;
    uint32_t string_ids_off_;
    uint32_t type_ids_size_;
    uint32_t type_ids_off_;
    uint32_t proto_ids_size_;
    uint32_t proto_ids_off_;
    uint32_t field_ids_size_;
    uint32_t field_ids_off_;
    uint32_t method_ids_size_;
    uint32_t method_ids_off_;
    uint32_t class_defs_size_;
    uint32_t class_defs_off_;
    uint32_t data_size_;
    uint32_t data_off_;
};

// DexFile 结构 (ART 内部结构)
// arm64: 有虚函数表指针 (vptr, 8字节)
// arm32: 没有虚函数表指针
struct DexFile32 {
    const uint8_t* begin_;
    size_t size_;
};

struct DexFile64 {
    void* vptr;
    const uint8_t* begin_;
    size_t size_;
};

// 全局变量
static bool g_is_64bit = false;
static bool g_arch_detected = false;
static bool g_loadclass_hooked = false;
static char g_hook_output_dir[512] = {0};
static std::atomic<int> g_dex_count{0};
static std::set<const uint8_t*> g_dumped_dex_addrs;
static std::map<const uint8_t*, size_t> g_dex_sizes;
static std::map<const uint8_t*, std::string> g_dex_paths;
static std::mutex g_dump_mutex;

// 原始函数指针
typedef void (*LoadClassFunc_Handle)(void* thiz, void* self, const void* dex_file,
                                      const void* class_def, uint64_t handle_value);
typedef void (*LoadClassFunc_Bool)(void* thiz, void* self, const void* dex_file,
                                    const void* class_def, bool add_to_table);
typedef void (*LoadClassFunc_Simple)(void* thiz, void* self, const void* dex_file,
                                      const void* class_def);
static LoadClassFunc_Handle g_orig_loadclass_handle = nullptr;
static LoadClassFunc_Bool g_orig_loadclass_bool = nullptr;
static LoadClassFunc_Simple g_orig_loadclass_simple = nullptr;
static int g_current_proxy_type = 0;

// 判断架构
static bool isCurrentArch64bit() {
#if defined(__aarch64__)
    return true;
#elif defined(__arm__)
    return false;
#else
    return sizeof(void*) == 8;
#endif
}

static void ensureArchDetected() {
    if (!g_arch_detected) {
        g_is_64bit = isCurrentArch64bit();
        g_arch_detected = true;
        LOGD("Architecture: %s", g_is_64bit ? "arm64" : "arm32");
    }
}

// 写入 DEX 文件
static bool writeDexToFile(const uint8_t* data, size_t size, const char* path) {
    if (data == nullptr || size == 0 || path == nullptr) {
        return false;
    }
    FILE* file = fopen(path, "wb");
    if (file == nullptr) {
        LOGE("Failed to open: %s", path);
        return false;
    }
    size_t written = fwrite(data, 1, size, file);
    fclose(file);
    return written == size;
}

// 前向声明(定义在文件后部)
static bool isReadableDex(const uint8_t* begin);
static bool isLikelyDexStructure(const uint8_t* p, size_t avail_len);
static size_t calculateDexSizeByHeader(const uint8_t* base);
static size_t fixDexHeaderInPlace(uint8_t* p, size_t avail_len);
static size_t calculateDexSize(const uint8_t* base);
static bool isReadableAddress(const void* addr, size_t size);
static size_t deriveDexSizeFromHeader(const uint8_t* begin, size_t hinted_size,
                                      const char** source_out);
static std::vector<std::string> getDexClassNamesFromCookie(jlong cookie);
static bool dumpDexMethodCodeItemsFromCookie(jlong cookie, const char* output_path);

static uintptr_t addressForMaps(const void* addr) {
    uintptr_t value = reinterpret_cast<uintptr_t>(addr);
#if defined(__aarch64__)
    // /proc/self/maps reports untagged virtual addresses, while ART heap
    // pointers may carry an MTE/TBI tag in the top byte (for example 0xb4...).
    return value & 0x00ffffffffffffffULL;
#else
    return value;
#endif
}

// 返回从 addr 开始的连续可读映射长度。DEX 映射可能跨多个 /proc/self/maps
// 区段，不能只用一次整段可读判断，否则合法 cookie 会全部被误拒绝。
static size_t readableSpan(const void* addr) {
    if (addr == nullptr) return 0;
    uintptr_t target = addressForMaps(addr);
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return 0;
    char line[1024];
    while (fgets(line, sizeof(line), maps)) {
        unsigned long long start = 0, end = 0;
        char perms[5] = {0};
        if (sscanf(line, "%llx-%llx %4s", &start, &end, perms) != 3 || perms[0] != 'r') continue;
        if (target >= static_cast<uintptr_t>(start) && target < static_cast<uintptr_t>(end)) {
            fclose(maps);
            return static_cast<size_t>(static_cast<uintptr_t>(end) - target);
        }
    }
    fclose(maps);
    return 0;
}

static constexpr size_t MAX_COOKIE_DUMP_SIZE = 100 * 1024 * 1024;

// 增强: 写盘前尽可能恢复被清零的 dex 头。
// data 可能为只读内存(壳用 mprotect 保护), 不能就地改写 ->
// 拷贝一份到堆上, 重建头部后写盘。
// 返回 true 表示已写出(文件名固定为 path)。
static bool writeDexWithHeaderFix(const uint8_t* data, size_t size, const char* path,
                                  bool force_header_fix) {
    if (data == nullptr || size == 0 || path == nullptr) return false;
    bool magic_ok = isReadableDex(data);
    bool struct_ok = isLikelyDexStructure(data, size);
    if (magic_ok) {
        return writeDexToFile(data, size, path);
    }
    if (!struct_ok && !force_header_fix) {
        // 既不满足 magic 也不满足结构 -> 无法确认是 dex, 放弃
        return false;
    }
    // magic 被清(或结构完好): 拷贝并重建头
    uint8_t* copy = (uint8_t*)malloc(size);
    if (copy == nullptr) return false;
    memcpy(copy, data, size);
    size_t fixed_size = fixDexHeaderInPlace(copy, size);
    bool ok = false;
    if (fixed_size != 0) {
        ok = writeDexToFile(copy, fixed_size, path);
        if (ok) {
            LOGD("DEX header rebuilt: %s (magic was erased by packer, size=%zu)", path, fixed_size);
        }
    } else {
        LOGE("DEX header rebuild failed (structure invalid): %s", path);
    }
    free(copy);
    return ok;
}

static bool isReadableDex(const uint8_t* begin) {
    return begin != nullptr && begin[0] == 'd' && begin[1] == 'e' && begin[2] == 'x' && begin[3] == '\n';
}

// ==================== 增强: Dex 头重建与准结构识别 ====================
// 原理: 某些加固只清零 dex 前 32 字节(magic 8 + checksum 4 + signature 20)。
//       0x20 之后的 file_size_/header_size_/endian_tag_/各 section off/size 均保留。
//       magic 是固定常量; checksum(Adler32) 可对 data[0x0C:] 重算;
//       signature(SHA-1) 可对 data[0x20:] 重算 -> 前 32 字节无损恢复。

// 仅校验 dex 0x20 之后的"结构不变量"(magic 是否完好无关紧要)
static bool isLikelyDexStructure(const uint8_t* p, size_t avail_len) {
    if (p == nullptr || avail_len < 0x70 + 4) return false;
    uint32_t header_size = 0, endian_tag = 0, data_off = 0, data_size = 0, file_size = 0;
    memcpy(&header_size, p + 0x24, sizeof(header_size));
    memcpy(&endian_tag, p + 0x28, sizeof(endian_tag));
    memcpy(&data_off, p + 0x6C, sizeof(data_off));
    memcpy(&data_size, p + 0x68, sizeof(data_size));
    memcpy(&file_size, p + 0x20, sizeof(file_size));
    if (header_size != 0x70) return false;
    if (endian_tag != 0x12345678) return false;
    if (data_off < 0x70 || data_size == 0) return false;
    if (data_size > 100 * 1024 * 1024) return false;
    if (file_size > 0 && file_size < 0x70) return false;
    // 粗略一致性：data_off+data_size 不得超过文件/可用长度太多
    uint64_t rough = (uint64_t)data_off + data_size;
    if (file_size > 0 && rough > (uint64_t)file_size + 0x100) return false;
    if (rough > avail_len + 0x100000) return false;
    return true;
}

// 计算 DEX 大小(不依赖 magic, 直接按 header 字段；供准结构扫描使用)
static size_t calculateDexSizeByHeader(const uint8_t* base) {
    if (base == nullptr) return 0;
    uint32_t data_off = 0, data_size = 0, file_size = 0;
    memcpy(&data_off, base + 0x6C, sizeof(data_off));
    memcpy(&data_size, base + 0x68, sizeof(data_size));
    memcpy(&file_size, base + 0x20, sizeof(file_size));
    uint64_t data_end = static_cast<uint64_t>(data_off) + data_size;
    if (data_size > 0 && data_off >= 0x70 && data_end <= 100 * 1024 * 1024) {
        return static_cast<size_t>(data_end);
    }
    if (file_size > 0 && file_size <= 100 * 1024 * 1024 && file_size >= 0x70) {
        return file_size;
    }
    return 0;
}

static void writeU32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v); p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

// Adler32: 与 zlib/ART 相同(模 65521, a 初值 1, b 初值 0)
static uint32_t dexAdler32(const uint8_t* data, size_t len) {
    const uint32_t MOD = 65521;
    uint32_t a = 1, b = 0;
    // 每轮最多 5552 字节, 避免 a/b 溢出
    size_t i = 0;
    while (i < len) {
        size_t block = len - i;
        if (block > 5552) block = 5552;
        for (size_t k = 0; k < block; k++) {
            a += data[i + k];
            b += a;
        }
        a %= MOD; b %= MOD;
        i += block;
    }
    return (b << 16) | a;
}

// SHA-1 实现(magic 被清时无法依赖系统库; 纯 C 实现, FIPS 180-1)
struct Sha1Ctx { uint32_t h[5]; uint64_t len; uint8_t buf[64]; size_t buflen; };
static void sha1Init(Sha1Ctx* c) {
    c->h[0]=0x67452301; c->h[1]=0xEFCDAB89; c->h[2]=0x98BADCFE; c->h[3]=0x10325476; c->h[4]=0xC3D2E1F0;
    c->len=0; c->buflen=0;
}
static uint32_t sha1Rol(uint32_t v, int n) { return (v << n) | (v >> (32 - n)); }
static void sha1Block(Sha1Ctx* c, const uint8_t* p) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)p[i*4] << 24) | ((uint32_t)p[i*4+1] << 16) | ((uint32_t)p[i*4+2] << 8) | p[i*4+3];
    }
    for (int i = 16; i < 80; i++) {
        w[i] = sha1Rol(w[i-3] ^ w[i-8] ^ w[i-14] ^ w[i-16], 1);
    }
    uint32_t a=c->h[0], b=c->h[1], cc=c->h[2], d=c->h[3], e=c->h[4];
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20)      { f = (b & cc) | (~b & d); k = 0x5A827999; }
        else if (i < 40) { f = b ^ cc ^ d;          k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & cc) | (b & d) | (cc & d); k = 0x8F1BBCDC; }
        else             { f = b ^ cc ^ d;          k = 0xCA62C1D6; }
        uint32_t tmp = sha1Rol(a, 5) + f + e + k + w[i];
        e = d; d = cc; cc = sha1Rol(b, 30); b = a; a = tmp;
    }
    c->h[0]+=a; c->h[1]+=b; c->h[2]+=cc; c->h[3]+=d; c->h[4]+=e;
}
static void sha1Update(Sha1Ctx* c, const uint8_t* data, size_t len) {
    c->len += len;
    if (c->buflen) {
        size_t need = 64 - c->buflen;
        size_t take = len < need ? len : need;
        memcpy(c->buf + c->buflen, data, take);
        c->buflen += take; data += take; len -= take;
        if (c->buflen == 64) { sha1Block(c, c->buf); c->buflen = 0; }
    }
    while (len >= 64) { sha1Block(c, data); data += 64; len -= 64; }
    if (len) { memcpy(c->buf, data, len); c->buflen = len; }
}
static void sha1Final(Sha1Ctx* c, uint8_t out[20]) {
    uint64_t bitlen = c->len * 8;
    uint8_t pad = 0x80;
    sha1Update(c, &pad, 1);
    uint8_t zero = 0;
    while (c->buflen != 56) sha1Update(c, &zero, 1);
    uint8_t lenb[8];
    for (int i = 0; i < 8; i++) lenb[i] = (uint8_t)(bitlen >> (56 - i * 8));
    sha1Update(c, lenb, 8);
    for (int i = 0; i < 5; i++) {
        out[i*4]   = (uint8_t)(c->h[i] >> 24);
        out[i*4+1] = (uint8_t)(c->h[i] >> 16);
        out[i*4+2] = (uint8_t)(c->h[i] >> 8);
        out[i*4+3] = (uint8_t)c->h[i];
    }
}

// 按 dex header 重建前 32 字节(就地写回)。要求 p 指向 dex 基址且 0x20 之后字段完好。
// 顺序(重要): 先写 magic -> 再写 signature(SHA-1 覆盖 0x20..end)
//            -> 最后写 checksum(Adler32 覆盖 0x0C..end, 含 signature 区域)。
// 返回重建后的大小(0 表示不可重建)。
static size_t fixDexHeaderInPlace(uint8_t* p, size_t avail_len) {
    if (p == nullptr || avail_len < 0x70 + 4) return 0;
    if (!isLikelyDexStructure(p, avail_len)) {
        // 允许完全被清的场景: 若 0x20 处连 file_size 都为 0, 无法重建
        return 0;
    }
    size_t size = calculateDexSizeByHeader(p);
    if (size == 0 || size > avail_len) return 0;
    // magic: "dex\n<ver>\0"。若版本字节残留则保留, 否则默认现代 Android 的 039。
    static const uint8_t DEX_MAGIC_035[8] = {'d','e','x','\n','0','3','5','\0'};
    static const uint8_t DEX_MAGIC_037[8] = {'d','e','x','\n','0','3','7','\0'};
    static const uint8_t DEX_MAGIC_038[8] = {'d','e','x','\n','0','3','8','\0'};
    static const uint8_t DEX_MAGIC_039[8] = {'d','e','x','\n','0','3','9','\0'};
    uint8_t magic[8];
    bool haveVersion = false;
    if (avail_len >= 8) {
        if (p[4] == '0' && p[5] == '3' &&
            (p[6] == '5' || p[6] == '7' || p[6] == '8' || p[6] == '9') && p[7] == 0) {
            memcpy(magic, p, 8);
            haveVersion = true;
        }
    }
    if (!haveVersion) {
        // 全清场景: 版本不可考, 取 039(Android 12+ 默认)。
        // 注意: checksum 覆盖 0x0C..end, 不含 magic, 所以版本选择不影响校验合法性。
        memcpy(magic, DEX_MAGIC_039, 8);
    }
    memcpy(p, magic, 8);

    // signature: SHA-1(data[0x20:]) —— 必须先于 checksum
    Sha1Ctx ctx;
    sha1Init(&ctx);
    sha1Update(&ctx, p + 32, size - 32);
    uint8_t sig[20];
    sha1Final(&ctx, sig);
    memcpy(p + 12, sig, 20);

    // checksum: Adler32(data[0x0C:]) —— 覆盖 signature 区域, 最后算
    uint32_t adler = dexAdler32(p + 12, size - 12);
    writeU32(p + 8, adler);
    return size;
}

// 宽松读取: 兼容"magic 完好"与"magic 被清零、结构完好"两种 dex
static bool getDexDataLenient(const uint8_t* base, size_t hinted_size, size_t* out_size) {
    if (base == nullptr || out_size == nullptr) return false;
    if (isReadableDex(base)) {
        size_t s = calculateDexSize(base);
        if (s != 0) { *out_size = s; return true; }
    }
    if (isLikelyDexStructure(base, hinted_size > 0 ? hinted_size : 100 * 1024 * 1024)) {
        size_t s = calculateDexSizeByHeader(base);
        if (s != 0) { *out_size = s; return true; }
    }
    return false;
}

// 计算 DEX 大小
static size_t calculateDexSize(const uint8_t* base) {
    if (base == nullptr) return 0;
    if (base[0] != 'd' || base[1] != 'e' || base[2] != 'x' || base[3] != '\n') return 0;

    DexHeader* header = (DexHeader*)base;
    uint32_t data_off = header->data_off_;
    uint32_t data_size = header->data_size_;
    uint32_t file_size = header->file_size_;

    uint64_t data_end = static_cast<uint64_t>(data_off) + data_size;
    if (data_size > 0 && data_off >= sizeof(DexHeader) && data_end <= 100 * 1024 * 1024) {
        return static_cast<size_t>(data_end);
    }
    if (file_size > 0 && file_size <= 100 * 1024 * 1024) {
        return file_size;
    }
    return 0;
}

static bool tryDexFileLayout(const void* object, size_t begin_offset, size_t size_offset,
                             const char* layout_name, const uint8_t** begin_out,
                             size_t* size_out) {
    size_t required = size_offset + sizeof(size_t);
    if (!isReadableAddress(object, required)) return false;

    const uint8_t* begin = nullptr;
    size_t size = 0;
    const uint8_t* raw = reinterpret_cast<const uint8_t*>(object);
    memcpy(&begin, raw + begin_offset, sizeof(begin));
    memcpy(&size, raw + size_offset, sizeof(size));
    if (begin == nullptr) return false;
    // 允许厂商 ART 把 size 放在不同位置，或把 DEX 分成多个映射区段。
    // 不检查 magic/header/结构；仅使用映射跨度和硬上限保证不会越界。
    size_t hinted_size = (size <= MAX_COOKIE_DUMP_SIZE) ? size : 0;
    size_t span = readableSpan(begin);
    if (span == 0) return false;

    const char* size_source = "cookie-data-size";
    size_t derived_size = deriveDexSizeFromHeader(begin, hinted_size, &size_source);
    if (derived_size == 0) return false;
    size = derived_size;

    *begin_out = begin;
    *size_out = size;
    LOGD("Cookie layout=%s ref=%p begin=%p size=%zu source=%s hinted=%zu",
         layout_name, object, begin, size, size_source, hinted_size);
    return true;
}

static bool probeDexFileObject(const void* object, const uint8_t** begin_out, size_t* size_out) {
    if (object == nullptr) return false;
    if (g_is_64bit) {
        // ART branches differ on whether DexFile has a virtual-table pointer.
        // Android 8-16 AOSP DexFile: begin_(0x00), unused_size_(0x08),
        // ArrayRef<const uint8_t> data_ {ptr@0x10, size@0x18}.
        if (tryDexFileLayout(object, 0, 24, "aosp-data-arrayref", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 8, 16, "arm64-vptr", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 0, 8, "arm64-plain", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 16, 24, "arm64-prefix16", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 24, 32, "arm64-prefix24", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 32, 40, "arm64-prefix32", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 40, 48, "arm64-prefix40", begin_out, size_out)) return true;
    } else {
        // 32-bit layout: begin_(0x00), unused_size_(0x04),
        // ArrayRef data {ptr@0x08, size@0x0c}.
        if (tryDexFileLayout(object, 0, 12, "aosp32-data-arrayref", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 4, 8, "arm32-vptr", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 0, 4, "arm32-plain", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 8, 12, "arm32-prefix8", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 12, 16, "arm32-prefix12", begin_out, size_out)) return true;
        if (tryDexFileLayout(object, 16, 20, "arm32-prefix16", begin_out, size_out)) return true;
    }
    return false;
}

static bool getDexBeginAndSizeFromRef(const void* dex_file_ref, const uint8_t** begin_out, size_t* size_out) {
    if (dex_file_ref == nullptr || begin_out == nullptr || size_out == nullptr) return false;
    ensureArchDetected();

    if (probeDexFileObject(dex_file_ref, begin_out, size_out)) return true;

    // Some vendor ART builds expose a cookie entry that points to a NativeDexFile
    // wrapper. The DexFile pointer may be at offset 0/8/16/24/32.
    const size_t pointer_offsets[] = {0, 8, 16, 24, 32, 40};
    for (size_t offset : pointer_offsets) {
        if (!isReadableAddress(dex_file_ref, offset + sizeof(void*))) continue;
        const uint8_t* indirect = nullptr;
        memcpy(&indirect, reinterpret_cast<const uint8_t*>(dex_file_ref) + offset,
               sizeof(indirect));
        if (indirect != nullptr && indirect != dex_file_ref
                && probeDexFileObject(indirect, begin_out, size_out)) {
            LOGD("Cookie indirect ref=%p offset=%zu object=%p", dex_file_ref, offset, indirect);
            return true;
        }
    }

    uintptr_t words[4] = {0, 0, 0, 0};
    if (isReadableAddress(dex_file_ref, sizeof(words))) {
        memcpy(words, dex_file_ref, sizeof(words));
    }
    LOGW("Cookie parse failed ref=%p words=%llx,%llx,%llx,%llx", dex_file_ref,
         (unsigned long long)words[0], (unsigned long long)words[1],
         (unsigned long long)words[2], (unsigned long long)words[3]);
    return false;
}

static void registerDexOutput(const void* dex_file_ref, const char* path) {
    const uint8_t* begin = nullptr;
    size_t size = 0;
    if (!getDexBeginAndSizeFromRef(dex_file_ref, &begin, &size)) {
        LOGW("registerDexOutput rejected cookie ref=%p path=%s", dex_file_ref,
             path == nullptr ? "<null>" : path);
        return;
    }
    std::lock_guard<std::mutex> lock(g_dump_mutex);
    g_dex_sizes[begin] = size;
    if (path != nullptr && path[0] != '\0') {
        g_dex_paths[begin] = path;
    }
}

// 从 DexFile 引用提取 DEX 数据
static void dumpDexFromDexFileRef(const void* dex_file_ref) {
    if (dex_file_ref == nullptr) return;

    char output_dir[sizeof(g_hook_output_dir)] = {0};
    {
        std::lock_guard<std::mutex> lock(g_dump_mutex);
        if (g_hook_output_dir[0] == '\0') return;
        memcpy(output_dir, g_hook_output_dir, sizeof(output_dir));
    }

    const uint8_t* begin = nullptr;
    size_t actual_size = 0;
    if (!getDexBeginAndSizeFromRef(dex_file_ref, &begin, &actual_size)) return;

    {
        std::lock_guard<std::mutex> lock(g_dump_mutex);
        g_dex_sizes[begin] = actual_size;
        if (g_dumped_dex_addrs.find(begin) != g_dumped_dex_addrs.end()) {
            return;
        }
        g_dumped_dex_addrs.insert(begin);
    }

    char output_path[1024];
    snprintf(output_path, sizeof(output_path), "%s/dex_%llx_%zx.dex", output_dir, (unsigned long long)begin, actual_size);

    if (writeDexToFile(begin, actual_size, output_path)) {
        {
            std::lock_guard<std::mutex> lock(g_dump_mutex);
            g_dex_paths[begin] = output_path;
        }
        g_dex_count++;
        LOGD("DEX saved: %s (size: %zu)", output_path, actual_size);
    }
}

// ==================== LoadClass 代理函数 ====================

static void LoadClassProxy_Handle(void* thiz, void* self, const void* dex_file,
                                   const void* class_def, uint64_t handle_value) {
    dumpDexFromDexFileRef(dex_file);
    if (g_orig_loadclass_handle != nullptr) {
        g_orig_loadclass_handle(thiz, self, dex_file, class_def, handle_value);
    }
}

static void LoadClassProxy_Bool(void* thiz, void* self, const void* dex_file,
                                 const void* class_def, bool add_to_table) {
    dumpDexFromDexFileRef(dex_file);
    if (g_orig_loadclass_bool != nullptr) {
        g_orig_loadclass_bool(thiz, self, dex_file, class_def, add_to_table);
    }
}

static void LoadClassProxy_Simple(void* thiz, void* self, const void* dex_file,
                                   const void* class_def) {
    dumpDexFromDexFileRef(dex_file);
    if (g_orig_loadclass_simple != nullptr) {
        g_orig_loadclass_simple(thiz, self, dex_file, class_def);
    }
}

// LoadClass 符号信息
typedef struct {
    int min_api;
    int max_api;
    const char* symbol;
    void* proxy_func;
    int proxy_type;
} LoadClassSymbolInfo;

static const LoadClassSymbolInfo LOADCLASS_SYMBOLS[] = {
    {36, 36, "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefENS_6HandleINS_6mirror5ClassEEE", (void*)LoadClassProxy_Handle, 1},
    {35, 35, "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefENS_6HandleINS_6mirror5ClassEEE", (void*)LoadClassProxy_Handle, 1},
    {34, 34, "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefEb", (void*)LoadClassProxy_Bool, 2},
    {33, 33, "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefEb", (void*)LoadClassProxy_Bool, 2},
    {31, 32, "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefEb", (void*)LoadClassProxy_Bool, 2},
    {28, 30, "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefE", (void*)LoadClassProxy_Simple, 3},
    {-1, -1, nullptr, nullptr, 0}
};

static int getAndroidApiLevel() {
    char prop[128] = {0};
    if (__system_property_get("ro.build.version.sdk", prop) > 0) {
        return atoi(prop);
    }
    return 0;
}

static const LoadClassSymbolInfo* getLoadClassSymbolInfo(int api_level) {
    for (int i = 0; LOADCLASS_SYMBOLS[i].symbol != nullptr; i++) {
        if (api_level >= LOADCLASS_SYMBOLS[i].min_api && api_level <= LOADCLASS_SYMBOLS[i].max_api) {
            return &LOADCLASS_SYMBOLS[i];
        }
    }
    return nullptr;
}

// ==================== JNI 方法 ====================

extern "C"
JNIEXPORT void JNICALL
Java_com_zitan_cdumpdex_MainHook_registerDexFileOutput(JNIEnv* env, jobject thiz, jlong cookie,
                                                       jstring absolute_path) {
    const char* outPath = env->GetStringUTFChars(absolute_path, nullptr);
    if (outPath == nullptr) return;
    registerDexOutput(reinterpret_cast<void*>(cookie), outPath);
    env->ReleaseStringUTFChars(absolute_path, outPath);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_zitan_cdumpdex_MainHook_writeDexToFile(JNIEnv* env, jobject thiz, jlong cookie,
                                                    jstring absolute_path) {
    const char* outPath = env->GetStringUTFChars(absolute_path, nullptr);
    if (outPath == nullptr) return JNI_FALSE;
    if (cookie == 0) {
        env->ReleaseStringUTFChars(absolute_path, outPath);
        return JNI_FALSE;
    }

    const uint8_t* data = nullptr;
    size_t size = 0;
    if (!getDexBeginAndSizeFromRef(reinterpret_cast<void*>(cookie), &data, &size)) {
        LOGW("writeDexToFile rejected cookie ref=%p path=%s", reinterpret_cast<void*>(cookie), outPath);
        env->ReleaseStringUTFChars(absolute_path, outPath);
        return JNI_FALSE;
    }

    // 按 Cookie 中 ART DexFile 保存的 begin/size 原样导出，不检查或重建 DEX 头。
    bool result = writeDexToFile(data, size, outPath);
    if (result) {
        std::lock_guard<std::mutex> lock(g_dump_mutex);
        g_dex_sizes[data] = size;
        g_dex_paths[data] = outPath;
    }
    env->ReleaseStringUTFChars(absolute_path, outPath);
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_zitan_cdumpdex_MainHook_getDexClassNames(JNIEnv* env, jobject thiz, jlong cookie) {
    std::vector<std::string> names = getDexClassNamesFromCookie(cookie);
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(names.size()), string_class, nullptr);
    if (result == nullptr) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(names.size()); ++i) {
        jstring value = env->NewStringUTF(names[static_cast<size_t>(i)].c_str());
        if (value == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_zitan_cdumpdex_MainHook_dumpDexMethodCodeItems(JNIEnv* env, jobject thiz,
                                                         jlong cookie, jstring absolute_path) {
    if (cookie == 0 || absolute_path == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(absolute_path, nullptr);
    if (path == nullptr) return JNI_FALSE;
    bool result = dumpDexMethodCodeItemsFromCookie(cookie, path);
    env->ReleaseStringUTFChars(absolute_path, path);
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_zitan_cdumpdex_MainHook_dumpDexByMemoryScan(JNIEnv* env, jobject thiz,
                                                       jstring output_dir) {
    const char* outputDir = env->GetStringUTFChars(output_dir, nullptr);
    if (outputDir == nullptr) return 0;

    FILE* maps_file = fopen("/proc/self/maps", "r");
    if (maps_file == nullptr) {
        env->ReleaseStringUTFChars(output_dir, outputDir);
        return 0;
    }

    int dex_count = 0;
    char line[512];

    while (fgets(line, sizeof(line), maps_file)) {
        uint64_t start, end;
        char perms[5];
        uint64_t offset;
        char dev[6];
        uint64_t inode;
        char pathname[256] = {0};

        int parsed = sscanf(line, "%llx-%llx %4s %llx %5s %llu %255[^\n]",
                           (unsigned long long*)&start, (unsigned long long*)&end,
                           perms, (unsigned long long*)&offset, dev,
                           (unsigned long long*)&inode, pathname);
        if (parsed < 6) continue;
        if (perms[0] != 'r') continue;

        size_t region_size = end - start;
        if (region_size < sizeof(DexHeader)) continue;

        uint8_t* region_start = (uint8_t*)start;

        for (size_t offset_in_region = 0; offset_in_region + sizeof(DexHeader) < region_size; offset_in_region += 4) {
            uint8_t* candidate = region_start + offset_in_region;
            bool magic_ok = (candidate[0] == 'd' && candidate[1] == 'e' && candidate[2] == 'x' && candidate[3] == '\n');
            // 增强: 除 magic 外, 支持"准结构匹配" —— 壳清零前 32 字节后 magic 消失,
            // 但 header_size(0x24)/endian_tag(0x28)/data_off(0x6C) 等结构字段仍在。
            // 命中准结构后走固定值校验 + 头重建, 即可还原被毁的 dex。
            size_t remaining = region_size - offset_in_region;
            bool struct_ok = isLikelyDexStructure(candidate, remaining);

            if (magic_ok) {
                DexHeader* header = (DexHeader*)candidate;
                uint32_t file_size = header->file_size_;

                if (file_size < sizeof(DexHeader) || file_size > 50 * 1024 * 1024) continue;
                if (offset_in_region + file_size > region_size) continue;

                // Filter garbage: validate header fields
                if (header->header_size_ != sizeof(DexHeader)) continue;
                if (header->endian_tag_ != 0x12345678) continue;
                if (header->string_ids_size_ == 0) continue;
                if (header->data_off_ < sizeof(DexHeader)) continue;
                if (header->data_size_ == 0) continue;
                if (file_size < 1024) continue;

                // Verify Adler32 checksum
                {
                    uint32_t a = 1, b = 0;
                    for (size_t k = 12; k < file_size; k++) {
                        a = (a + candidate[k]) % 65521;
                        b = (b + a) % 65521;
                    }
                    uint32_t computed = (b << 16) | a;
                    if (computed != header->checksum_) continue;
                }

                char output_path[512];
                snprintf(output_path, sizeof(output_path), "%s/memscan_%d.dex", outputDir, dex_count);

                if (writeDexToFile(candidate, file_size, output_path)) {
                    dex_count++;
                    offset_in_region += (file_size - sizeof(DexHeader));
                }
            } else if (struct_ok) {
                // 准结构命中: magic 被清零。计算大小并重建头部。
                size_t dex_size = calculateDexSizeByHeader(candidate);
                if (dex_size == 0 || dex_size > 50 * 1024 * 1024) continue;
                if (offset_in_region + dex_size > region_size) continue;

                uint32_t string_ids_size = 0, data_off = 0, data_size = 0;
                memcpy(&string_ids_size, candidate + 0x38, sizeof(string_ids_size));
                memcpy(&data_off, candidate + 0x6C, sizeof(data_off));
                memcpy(&data_size, candidate + 0x68, sizeof(data_size));
                if (string_ids_size == 0 || data_off < 0x70 || data_size == 0) continue;

                char output_path[512];
                snprintf(output_path, sizeof(output_path), "%s/memscan_struct_%d.dex", outputDir, dex_count);

                if (writeDexWithHeaderFix(candidate, dex_size, output_path, true)) {
                    LOGD("struct-scan hit: 0x%llx size=%zu (header rebuilt, magic was erased)",
                         (unsigned long long)(uintptr_t)candidate, dex_size);
                    dex_count++;
                    offset_in_region += (dex_size - sizeof(DexHeader));
                }
            }
        }
    }

    fclose(maps_file);
    env->ReleaseStringUTFChars(output_dir, outputDir);
    return dex_count;
}

// LoadClass Hook 相关

extern "C"
JNIEXPORT void JNICALL
Java_com_zitan_cdumpdex_MainHook_setHookOutputDir(JNIEnv* env, jobject thiz, jstring output_dir) {
    const char* dir = env->GetStringUTFChars(output_dir, nullptr);
    if (dir != nullptr) {
        {
            std::lock_guard<std::mutex> lock(g_dump_mutex);
            strncpy(g_hook_output_dir, dir, sizeof(g_hook_output_dir) - 1);
            g_hook_output_dir[sizeof(g_hook_output_dir) - 1] = '\0';
            g_dex_count.store(0);
            g_dumped_dex_addrs.clear();
            g_dex_sizes.clear();
            g_dex_paths.clear();
        }
        LOGD("Output dir: %s", dir);
        env->ReleaseStringUTFChars(output_dir, dir);
    }
}

static bool isReadableAddress(const void* addr, size_t size) {
    if (addr == nullptr || size == 0) return false;
    uintptr_t target = addressForMaps(addr);
    if (size > std::numeric_limits<uintptr_t>::max() - target) return false;
    uintptr_t target_end = target + size;
    uintptr_t covered_until = target;
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;
    char line[1024];
    while (fgets(line, sizeof(line), maps)) {
        unsigned long long start = 0, end = 0;
        char perms[5] = {0};
        if (sscanf(line, "%llx-%llx %4s", &start, &end, perms) != 3) continue;
        if (perms[0] != 'r') continue;
        if (covered_until < start) {
            if (covered_until != target) break;
            continue;
        }
        if (covered_until >= start && covered_until < end) {
            covered_until = static_cast<uintptr_t>(end);
        }
        if (covered_until >= target_end) {
            fclose(maps);
            return true;
        }
    }
    fclose(maps);
    return false;
}

// Android ART 的 DexFile::Size() 实际返回 header_->file_size_。
// 加固代码可能把 file_size_ 清零或改成超出映射范围的值，此时使用
// data_off_ + data_size_ 作为文件末端。这里只计算导出长度，不验证
// magic、header_size、endian 或其它 DEX 结构。
static size_t deriveDexSizeFromHeader(const uint8_t* begin, size_t hinted_size,
                                      const char** source_out) {
    if (source_out != nullptr) *source_out = "none";
    if (begin == nullptr || !isReadableAddress(begin, 0x70)) return 0;

    uint32_t file_size = 0;
    uint32_t data_size = 0;
    uint32_t data_off = 0;
    memcpy(&file_size, begin + 0x20, sizeof(file_size));
    memcpy(&data_size, begin + 0x68, sizeof(data_size));
    memcpy(&data_off, begin + 0x6c, sizeof(data_off));

    uint64_t data_end64 = static_cast<uint64_t>(data_off) + data_size;
    bool data_end_valid = data_size != 0 && data_off >= 0x70
            && data_end64 <= MAX_COOKIE_DUMP_SIZE;
    size_t data_end = data_end_valid ? static_cast<size_t>(data_end64) : 0;
    bool data_end_readable = data_end_valid && isReadableAddress(begin, data_end);

    bool file_size_valid = file_size >= 0x70 && file_size <= MAX_COOKIE_DUMP_SIZE;
    bool file_size_readable = file_size_valid && isReadableAddress(begin, file_size);

    // file_size 小于 data 区末端属于明显异常，优先使用 data_end；
    // file_size 大于 data_end 仍保留原生 Size() 语义（可能包含尾部数据）。
    if (file_size_readable && (!data_end_readable || file_size >= data_end)) {
        if (source_out != nullptr) *source_out = "header.file_size";
        return file_size;
    }
    if (data_end_readable) {
        if (source_out != nullptr) *source_out = "header.data_end";
        return data_end;
    }
    if (hinted_size >= 0x70 && hinted_size <= MAX_COOKIE_DUMP_SIZE
            && isReadableAddress(begin, hinted_size)) {
        if (source_out != nullptr) *source_out = "cookie-data-size";
        return hinted_size;
    }
    return 0;
}

static bool dexRangeValid(size_t total, uint64_t off, uint64_t length) {
    return off <= total && length <= static_cast<uint64_t>(total) - off;
}

static bool readDexString(const uint8_t* base, size_t total, uint32_t string_data_off,
                          std::string* out) {
    if (out == nullptr || !dexRangeValid(total, string_data_off, 1)) return false;
    size_t pos = string_data_off;
    uint32_t utf16_size = 0;
    unsigned shift = 0;
    for (unsigned i = 0; i < 5; ++i) {
        if (!dexRangeValid(total, pos, 1)) return false;
        uint8_t byte = base[pos++];
        utf16_size |= static_cast<uint32_t>(byte & 0x7f) << shift;
        if ((byte & 0x80) == 0) break;
        shift += 7;
        if (i == 4) return false;
    }
    if (utf16_size > 1024 * 1024) return false;
    size_t end = pos;
    while (end < total && base[end] != 0) ++end;
    if (end >= total) return false;
    out->assign(reinterpret_cast<const char*>(base + pos), end - pos);
    return true;
}

static std::vector<std::string> getDexClassNamesFromCookie(jlong cookie) {
    std::vector<std::string> names;
    const uint8_t* base = nullptr;
    size_t hinted = 0;
    if (!getDexBeginAndSizeFromRef(reinterpret_cast<void*>(static_cast<uintptr_t>(cookie)), &base, &hinted)) {
        return names;
    }
    const char* source = nullptr;
    size_t total = deriveDexSizeFromHeader(base, hinted, &source);
    if (total < 0x70) return names;

    uint32_t string_ids_size = 0, string_ids_off = 0;
    uint32_t type_ids_size = 0, type_ids_off = 0;
    uint32_t class_defs_size = 0, class_defs_off = 0;
    memcpy(&string_ids_size, base + 0x38, 4);
    memcpy(&string_ids_off, base + 0x3c, 4);
    memcpy(&type_ids_size, base + 0x40, 4);
    memcpy(&type_ids_off, base + 0x44, 4);
    memcpy(&class_defs_size, base + 0x60, 4);
    memcpy(&class_defs_off, base + 0x64, 4);
    if (!dexRangeValid(total, string_ids_off, static_cast<uint64_t>(string_ids_size) * 4) ||
        !dexRangeValid(total, type_ids_off, static_cast<uint64_t>(type_ids_size) * 4) ||
        !dexRangeValid(total, class_defs_off, static_cast<uint64_t>(class_defs_size) * 32)) {
        return names;
    }
    names.reserve(class_defs_size);
    for (uint32_t i = 0; i < class_defs_size; ++i) {
        uint32_t class_idx = 0;
        memcpy(&class_idx, base + class_defs_off + static_cast<size_t>(i) * 32, 4);
        if (class_idx >= type_ids_size) continue;
        uint32_t descriptor_idx = 0;
        memcpy(&descriptor_idx, base + type_ids_off + static_cast<size_t>(class_idx) * 4, 4);
        if (descriptor_idx >= string_ids_size) continue;
        uint32_t string_data_off = 0;
        memcpy(&string_data_off, base + string_ids_off + static_cast<size_t>(descriptor_idx) * 4, 4);
        std::string descriptor;
        if (!readDexString(base, total, string_data_off, &descriptor)) continue;
        if (descriptor.size() >= 2 && descriptor.front() == 'L' && descriptor.back() == ';') {
            descriptor = descriptor.substr(1, descriptor.size() - 2);
            for (char& c : descriptor) if (c == '/') c = '.';
            names.push_back(descriptor);
        }
    }
    LOGD("C parsed cookie=%p classes=%zu size=%zu source=%s",
         reinterpret_cast<void*>(static_cast<uintptr_t>(cookie)), names.size(), total,
         source == nullptr ? "unknown" : source);
    return names;
}

static bool readUleb128(const uint8_t* base, size_t total, size_t* cursor, uint32_t* value) {
    if (base == nullptr || cursor == nullptr || value == nullptr) return false;
    uint32_t result = 0;
    unsigned shift = 0;
    for (unsigned i = 0; i < 5; ++i) {
        if (!dexRangeValid(total, *cursor, 1)) return false;
        uint8_t byte = base[(*cursor)++];
        result |= static_cast<uint32_t>(byte & 0x7f) << shift;
        if ((byte & 0x80) == 0) {
            *value = result;
            return true;
        }
        shift += 7;
    }
    return false;
}

static bool getStringByIndex(const uint8_t* base, size_t total, uint32_t string_ids_size,
                             uint32_t string_ids_off, uint32_t string_idx, std::string* out) {
    if (string_idx >= string_ids_size ||
        !dexRangeValid(total, static_cast<uint64_t>(string_ids_off) + string_idx * 4ULL, 4)) {
        return false;
    }
    uint32_t string_data_off = 0;
    memcpy(&string_data_off, base + string_ids_off + static_cast<size_t>(string_idx) * 4, 4);
    return readDexString(base, total, string_data_off, out);
}

static bool getTypeDescriptor(const uint8_t* base, size_t total,
                              uint32_t string_ids_size, uint32_t string_ids_off,
                              uint32_t type_ids_size, uint32_t type_ids_off,
                              uint32_t type_idx, std::string* out) {
    if (type_idx >= type_ids_size ||
        !dexRangeValid(total, static_cast<uint64_t>(type_ids_off) + type_idx * 4ULL, 4)) {
        return false;
    }
    uint32_t descriptor_idx = 0;
    memcpy(&descriptor_idx, base + type_ids_off + static_cast<size_t>(type_idx) * 4, 4);
    return getStringByIndex(base, total, string_ids_size, string_ids_off, descriptor_idx, out);
}

struct DexTextCache {
    std::vector<std::string> strings;
    std::vector<uint8_t> string_states;
    std::vector<std::string> types;
    std::vector<uint8_t> type_states;
};

static bool getCachedString(const uint8_t* base, size_t total,
                            uint32_t string_ids_size, uint32_t string_ids_off,
                            uint32_t string_idx, DexTextCache* cache, std::string* out) {
    if (cache == nullptr || out == nullptr || string_idx >= string_ids_size) return false;
    uint8_t& state = cache->string_states[string_idx];
    if (state == 0) {
        state = getStringByIndex(base, total, string_ids_size, string_ids_off,
                                 string_idx, &cache->strings[string_idx]) ? 1 : 2;
    }
    if (state != 1) return false;
    *out = cache->strings[string_idx];
    return true;
}

static bool getCachedType(const uint8_t* base, size_t total,
                          uint32_t string_ids_size, uint32_t string_ids_off,
                          uint32_t type_ids_size, uint32_t type_ids_off,
                          uint32_t type_idx, DexTextCache* cache, std::string* out) {
    if (cache == nullptr || out == nullptr || type_idx >= type_ids_size) return false;
    uint8_t& state = cache->type_states[type_idx];
    if (state == 0) {
        uint32_t descriptor_idx = 0;
        if (dexRangeValid(total, static_cast<uint64_t>(type_ids_off) + type_idx * 4ULL, 4)) {
            memcpy(&descriptor_idx, base + type_ids_off + static_cast<size_t>(type_idx) * 4, 4);
            state = getCachedString(base, total, string_ids_size, string_ids_off,
                                    descriptor_idx, cache, &cache->types[type_idx]) ? 1 : 2;
        } else {
            state = 2;
        }
    }
    if (state != 1) return false;
    *out = cache->types[type_idx];
    return true;
}

static std::string formatMethodSignature(const uint8_t* base, size_t total,
                                         uint32_t string_ids_size, uint32_t string_ids_off,
                                         uint32_t type_ids_size, uint32_t type_ids_off,
                                         uint32_t proto_ids_size, uint32_t proto_ids_off,
                                         uint32_t method_ids_size, uint32_t method_ids_off,
                                         uint32_t method_idx, DexTextCache* cache) {
    if (method_idx >= method_ids_size ||
        !dexRangeValid(total, static_cast<uint64_t>(method_ids_off) + method_idx * 8ULL, 8)) {
        return "<invalid-method>";
    }
    const uint8_t* method_id = base + method_ids_off + static_cast<size_t>(method_idx) * 8;
    uint16_t class_idx = 0, proto_idx = 0;
    uint32_t name_idx = 0;
    memcpy(&class_idx, method_id, 2);
    memcpy(&proto_idx, method_id + 2, 2);
    memcpy(&name_idx, method_id + 4, 4);
    std::string class_desc, method_name;
    if (!getCachedType(base, total, string_ids_size, string_ids_off,
                       type_ids_size, type_ids_off, class_idx, cache, &class_desc) ||
        !getCachedString(base, total, string_ids_size, string_ids_off,
                         name_idx, cache, &method_name) ||
        proto_idx >= proto_ids_size ||
        !dexRangeValid(total, static_cast<uint64_t>(proto_ids_off) + proto_idx * 12ULL, 12)) {
        return "<invalid-method>";
    }
    const uint8_t* proto_id = base + proto_ids_off + static_cast<size_t>(proto_idx) * 12;
    uint32_t return_type_idx = 0, parameters_off = 0;
    memcpy(&return_type_idx, proto_id + 4, 4);
    memcpy(&parameters_off, proto_id + 8, 4);
    std::string return_desc;
    if (!getCachedType(base, total, string_ids_size, string_ids_off,
                       type_ids_size, type_ids_off, return_type_idx, cache, &return_desc)) {
        return "<invalid-method>";
    }
    std::string params;
    if (parameters_off != 0) {
        if (!dexRangeValid(total, parameters_off, 4)) return "<invalid-method>";
        uint32_t parameter_count = 0;
        memcpy(&parameter_count, base + parameters_off, 4);
        if (!dexRangeValid(total, static_cast<uint64_t>(parameters_off) + 4,
                           static_cast<uint64_t>(parameter_count) * 2)) {
            return "<invalid-method>";
        }
        for (uint32_t i = 0; i < parameter_count; ++i) {
            uint16_t parameter_type_idx = 0;
            memcpy(&parameter_type_idx, base + parameters_off + 4 + static_cast<size_t>(i) * 2, 2);
            std::string parameter_desc;
            if (!getCachedType(base, total, string_ids_size, string_ids_off,
                               type_ids_size, type_ids_off, parameter_type_idx,
                               cache, &parameter_desc)) {
                return "<invalid-method>";
            }
            params += parameter_desc;
        }
    }
    return class_desc + "->" + method_name + "(" + params + ")" + return_desc;
}

typedef uint64_t (*GetCodeItemOffsetFunc)(const void*, const void*, uint32_t);

static GetCodeItemOffsetFunc resolveGetCodeItemOffset() {
    static GetCodeItemOffsetFunc function = nullptr;
    static bool resolved = false;
    if (!resolved) {
        resolved = true;
        function = reinterpret_cast<GetCodeItemOffsetFunc>(DobbySymbolResolver(
                "libdexfile.so",
                "_ZNK3art7DexFile17GetCodeItemOffsetERKNS_3dex8ClassDefEj"));
        if (function == nullptr) {
            void* handle = dlopen("libdexfile.so", RTLD_NOW | RTLD_NOLOAD);
            if (handle == nullptr) handle = dlopen("libdexfile.so", RTLD_NOW);
            if (handle != nullptr) {
                function = reinterpret_cast<GetCodeItemOffsetFunc>(dlsym(
                        handle, "_ZNK3art7DexFile17GetCodeItemOffsetERKNS_3dex8ClassDefEj"));
            }
        }
        LOGD("GetCodeItemOffset symbol=%p", reinterpret_cast<void*>(function));
    }
    return function;
}

static const uint8_t* getDexDataBase(const void* dex_file, const uint8_t* fallback) {
    if (dex_file == nullptr) return fallback;
    size_t data_offset = sizeof(void*) == 8 ? 0x10 : 0x08;
    if (!isReadableAddress(dex_file, data_offset + sizeof(void*))) return fallback;
    const uint8_t* data_base = nullptr;
    memcpy(&data_base, reinterpret_cast<const uint8_t*>(dex_file) + data_offset,
           sizeof(data_base));
    return data_base != nullptr ? data_base : fallback;
}

static void appendFormat(std::string* out, const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    if (n > 0) out->append(buffer, static_cast<size_t>(n < static_cast<int>(sizeof(buffer)) ? n : sizeof(buffer) - 1));
}

static void appendCodeItem(std::string* out, const uint8_t* code_item, size_t available,
                           uint32_t code_off, uint32_t encoded_code_off) {
    appendFormat(out, "code_off:0x%08x (class_data:0x%08x)\n", code_off, encoded_code_off);
    if (code_item == nullptr || available < 16) {
        out->append("code-item:<无法读取>\n");
        return;
    }
    uint16_t registers_size = 0, ins_size = 0, outs_size = 0, tries_size = 0;
    uint32_t debug_info_off = 0, insns_size = 0;
    memcpy(&registers_size, code_item, 2);
    memcpy(&ins_size, code_item + 2, 2);
    memcpy(&outs_size, code_item + 4, 2);
    memcpy(&tries_size, code_item + 6, 2);
    memcpy(&debug_info_off, code_item + 8, 4);
    memcpy(&insns_size, code_item + 12, 4);
    uint64_t insns_bytes64 = static_cast<uint64_t>(insns_size) * 2;
    if (insns_size > 0x1000000 || insns_bytes64 > available - 16) {
        out->append("code-item:<insns范围异常>\n");
        return;
    }
    appendFormat(out, "code-item: registers_size=%u, ins_size=%u, outs_size=%u, tries_size=%u, "
                       "debug_info_off=0x%08x, insns_size=%u\ninsns:",
                 registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size);
    static const char hex[] = "0123456789abcdef";
    for (uint32_t i = 0; i < insns_size; ++i) {
        uint16_t unit = 0;
        memcpy(&unit, code_item + 16 + static_cast<size_t>(i) * 2, 2);
        if ((i % 16) == 0) out->push_back('\n');
        out->push_back(hex[(unit >> 12) & 0xf]); out->push_back(hex[(unit >> 8) & 0xf]);
        out->push_back(hex[(unit >> 4) & 0xf]); out->push_back(hex[unit & 0xf]);
        if ((i % 16) != 15) out->push_back(' ');
    }
    out->push_back('\n');
}

static bool dumpDexMethodCodeItemsFromCookie(jlong cookie, const char* output_path) {
    if (cookie == 0 || output_path == nullptr) return false;
    const void* dex_file = reinterpret_cast<void*>(static_cast<uintptr_t>(cookie));
    const uint8_t* base = nullptr;
    size_t hinted = 0;
    if (!getDexBeginAndSizeFromRef(dex_file, &base, &hinted)) return false;
    const char* size_source = nullptr;
    size_t total = deriveDexSizeFromHeader(base, hinted, &size_source);
    if (total < 0x70) return false;

    uint32_t string_ids_size = 0, string_ids_off = 0, type_ids_size = 0, type_ids_off = 0;
    uint32_t proto_ids_size = 0, proto_ids_off = 0, method_ids_size = 0, method_ids_off = 0;
    uint32_t class_defs_size = 0, class_defs_off = 0;
    memcpy(&string_ids_size, base + 0x38, 4); memcpy(&string_ids_off, base + 0x3c, 4);
    memcpy(&type_ids_size, base + 0x40, 4); memcpy(&type_ids_off, base + 0x44, 4);
    memcpy(&proto_ids_size, base + 0x48, 4); memcpy(&proto_ids_off, base + 0x4c, 4);
    memcpy(&method_ids_size, base + 0x58, 4); memcpy(&method_ids_off, base + 0x5c, 4);
    memcpy(&class_defs_size, base + 0x60, 4); memcpy(&class_defs_off, base + 0x64, 4);
    if (!dexRangeValid(total, class_defs_off, static_cast<uint64_t>(class_defs_size) * 32) ||
        !dexRangeValid(total, method_ids_off, static_cast<uint64_t>(method_ids_size) * 8)) {
        return false;
    }
    GetCodeItemOffsetFunc get_offset = resolveGetCodeItemOffset();
    if (get_offset == nullptr) return false;
    FILE* file = fopen(output_path, "wb");
    if (file == nullptr) {
        LOGE("Failed to create code-item output: %s errno=%d", output_path, errno);
        return false;
    }
    const uint8_t* data_base = getDexDataBase(dex_file, base);
    DexTextCache text_cache;
    text_cache.strings.resize(string_ids_size);
    text_cache.string_states.assign(string_ids_size, 0);
    text_cache.types.resize(type_ids_size);
    text_cache.type_states.assign(type_ids_size, 0);
    std::string output;
    output.reserve(1024 * 1024);
    auto flushOutput = [&]() {
        if (!output.empty()) { fwrite(output.data(), 1, output.size(), file); output.clear(); }
    };
    size_t method_count = 0;
    for (uint32_t class_index = 0; class_index < class_defs_size; ++class_index) {
        const uint8_t* class_def = base + class_defs_off + static_cast<size_t>(class_index) * 32;
        uint32_t class_data_off = 0;
        memcpy(&class_data_off, class_def + 24, 4);
        if (class_data_off == 0 || !dexRangeValid(total, class_data_off, 1)) continue;
        size_t cursor = class_data_off;
        uint32_t static_fields = 0, instance_fields = 0, direct_methods = 0, virtual_methods = 0;
        if (!readUleb128(base, total, &cursor, &static_fields) ||
            !readUleb128(base, total, &cursor, &instance_fields) ||
            !readUleb128(base, total, &cursor, &direct_methods) ||
            !readUleb128(base, total, &cursor, &virtual_methods)) continue;
        uint64_t field_total = static_cast<uint64_t>(static_fields) + instance_fields;
        if (field_total > 10000000ULL) continue;
        for (uint64_t i = 0; i < field_total; ++i) {
            uint32_t ignored = 0;
            if (!readUleb128(base, total, &cursor, &ignored) ||
                !readUleb128(base, total, &cursor, &ignored)) {
                cursor = total;
                break;
            }
        }
        if (cursor >= total) continue;
        uint64_t encoded_method_total = static_cast<uint64_t>(direct_methods) + virtual_methods;
        if (encoded_method_total > method_ids_size) continue;
        uint32_t method_idx = 0;
        for (uint64_t i = 0; i < encoded_method_total; ++i) {
            if (i == direct_methods) method_idx = 0;
            uint32_t method_idx_diff = 0, access_flags = 0, encoded_code_off = 0;
            if (!readUleb128(base, total, &cursor, &method_idx_diff) ||
                !readUleb128(base, total, &cursor, &access_flags) ||
                !readUleb128(base, total, &cursor, &encoded_code_off)) break;
            if (method_idx_diff > UINT32_MAX - method_idx) break;
            method_idx += method_idx_diff;
            std::string signature = formatMethodSignature(base, total,
                    string_ids_size, string_ids_off, type_ids_size, type_ids_off,
                    proto_ids_size, proto_ids_off, method_ids_size, method_ids_off, method_idx,
                    &text_cache);
            output.append(signature);
            appendFormat(&output, "：方法idx:%u\n", method_idx);
            uint64_t optional = get_offset(dex_file, class_def, method_idx);
            bool has_value = (optional >> 32) != 0;
            uint32_t code_off = static_cast<uint32_t>(optional);
            if (!has_value || code_off == 0) {
                output.append("code-item:<无，native/abstract或方法不存在>\n\n");
                ++method_count;
                continue;
            }
            const uint8_t* code_item = data_base + code_off;
            size_t available = code_off < total ? total - code_off : 0;
            appendCodeItem(&output, code_item, available, code_off, encoded_code_off);
            output.push_back('\n');
            if (output.size() >= 1024 * 1024) flushOutput();
            ++method_count;
        }
    }
    flushOutput();
    fclose(file);
    LOGD("Method code-items saved cookie=%p methods=%zu path=%s",
         dex_file, method_count, output_path);
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_zitan_cdumpdex_MainHook_installLoadClassHook(JNIEnv* env, jobject thiz) {
    if (g_loadclass_hooked) {
        LOGD("Already hooked");
        return JNI_TRUE;
    }

    int api_level = getAndroidApiLevel();
    LOGD("API level: %d", api_level);

    void* orig_func = nullptr;
    const LoadClassSymbolInfo* info = getLoadClassSymbolInfo(api_level);

    if (info != nullptr) {
        LOGD("Trying: %s", info->symbol);

        void* target = DobbySymbolResolver("libart.so", info->symbol);
        if (target == nullptr) {
            LOGE("Symbol not found: %s", info->symbol);
        } else {
            LOGD("Symbol addr: %p", target);

            int ret = DobbyHook(target, info->proxy_func, &orig_func);
            if (ret == 0 && orig_func != nullptr) {
                LOGD("Hook succeeded! orig: %p", orig_func);
                g_current_proxy_type = info->proxy_type;
                switch (info->proxy_type) {
                    case 1: g_orig_loadclass_handle = (LoadClassFunc_Handle)orig_func; break;
                    case 2: g_orig_loadclass_bool = (LoadClassFunc_Bool)orig_func; break;
                    case 3: g_orig_loadclass_simple = (LoadClassFunc_Simple)orig_func; break;
                }
                g_loadclass_hooked = true;
                return JNI_TRUE;
            } else {
                LOGE("Hook failed: %d", ret);
            }
        }
    }

    // 遍历所有符号尝试
    LOGD("Trying all symbols...");
    for (int i = 0; LOADCLASS_SYMBOLS[i].symbol != nullptr; i++) {
        void* target = DobbySymbolResolver("libart.so", LOADCLASS_SYMBOLS[i].symbol);
        if (target == nullptr) continue;

        LOGD("Found %s at %p", LOADCLASS_SYMBOLS[i].symbol, target);

        orig_func = nullptr;
        int ret = DobbyHook(target, LOADCLASS_SYMBOLS[i].proxy_func, &orig_func);
        if (ret == 0 && orig_func != nullptr) {
            LOGD("Hook succeeded! orig: %p", orig_func);
            g_current_proxy_type = LOADCLASS_SYMBOLS[i].proxy_type;
            switch (LOADCLASS_SYMBOLS[i].proxy_type) {
                case 1: g_orig_loadclass_handle = (LoadClassFunc_Handle)orig_func; break;
                case 2: g_orig_loadclass_bool = (LoadClassFunc_Bool)orig_func; break;
                case 3: g_orig_loadclass_simple = (LoadClassFunc_Simple)orig_func; break;
            }
            g_loadclass_hooked = true;
            return JNI_TRUE;
        } else {
            LOGE("Hook failed: %d", ret);
        }
    }

    LOGE("All hook attempts failed");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_zitan_cdumpdex_MainHook_uninstallLoadClassHook(JNIEnv* env, jobject thiz) {
    g_loadclass_hooked = false;
    g_orig_loadclass_handle = nullptr;
    g_orig_loadclass_bool = nullptr;
    g_orig_loadclass_simple = nullptr;
    g_current_proxy_type = 0;
    LOGD("Hook uninstalled");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_zitan_cdumpdex_MainHook_getDumpedDexCount(JNIEnv* env, jobject thiz) {
    return g_dex_count;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_zitan_cdumpdex_MainHook_resetDumpCount(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_dump_mutex);
    g_dex_count.store(0);
    g_dumped_dex_addrs.clear();
    g_dex_sizes.clear();
    g_dex_paths.clear();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_zitan_cdumpdex_MainHook_isLoadClassHookActive(JNIEnv* env, jobject thiz) {
    return g_loadclass_hooked ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_zitan_cdumpdex_MainHook_getApiLevel(JNIEnv* env, jobject thiz) {
    return getAndroidApiLevel();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_zitan_cdumpdex_MainHook_listLoadClassSymbols(JNIEnv* env, jobject thiz) {
    char buffer[2048] = {0};
    int offset = 0;
    int api_level = getAndroidApiLevel();

    offset += snprintf(buffer + offset, sizeof(buffer) - offset, "API: %d\n\n", api_level);

    for (int i = 0; LOADCLASS_SYMBOLS[i].symbol != nullptr; i++) {
        void* sym = DobbySymbolResolver("libart.so", LOADCLASS_SYMBOLS[i].symbol);
        const char* status = (sym != nullptr) ? "FOUND" : "NOT FOUND";
        const char* current = (api_level >= LOADCLASS_SYMBOLS[i].min_api &&
                               api_level <= LOADCLASS_SYMBOLS[i].max_api) ? " [CURRENT]" : "";
        offset += snprintf(buffer + offset, sizeof(buffer) - offset,
                          "API %d-%d: %s%s\n",
                          LOADCLASS_SYMBOLS[i].min_api, LOADCLASS_SYMBOLS[i].max_api,
                          status, current);
    }

    return env->NewStringUTF(buffer);
}
