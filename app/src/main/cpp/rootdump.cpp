/**
 * rootdump - Standalone native executable for root-based DEX memory dumping.
 *
 * Usage: rootdump <pid> <output_dir>
 *
 * Reads /proc/<pid>/mem and scans for DEX magic signatures.
 * Only scans the target process memory; it never injects code or triggers class loading.
 * for the active class-loading trigger before running rootdump.
 *
 * Build: standalone executable, no dependencies.
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cstdarg>
#include <cerrno>

#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>

// DEX Header structure (mirrors ART's dex_file.h)
struct DexHeader {
    uint8_t  magic_[8];
    uint32_t checksum_;
    uint8_t  signature_[20];
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

struct MapList { uint32_t size_; };
struct MapItem { uint16_t type_; uint16_t unused_; uint32_t size_; uint32_t offset_; };

static const size_t DEX_HEADER_SIZE   = sizeof(DexHeader);
static const size_t MAX_DEX_SIZE      = 100 * 1024 * 1024;
static const size_t MIN_VALID_DEX_SIZE = 1024;
static const size_t SCAN_CHUNK_SIZE   = 64 * 1024;
static const size_t MAX_REGIONS       = 2048;
static const size_t MAX_LINE          = 1024;

struct MemRegion {
    uint64_t start, end;
    bool readable, executable;
    char pathname[512];
};

static void log_info(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
    fprintf(stderr, "\n");
}

static bool shouldExcludeRegion(const char* pathname) {
    if (pathname == nullptr || pathname[0] == '\0') return false;
    const char* excludePatterns[] = {
        "/apex/", "/system/framework/", "/system/lib", "/system/lib64",
        "/vendor/", "/product/", "/odm/", "/data/dalvik-cache/", nullptr
    };
    for (int i = 0; excludePatterns[i] != nullptr; i++)
        if (strstr(pathname, excludePatterns[i]) != nullptr) return true;
    return false;
}

static bool isDexMagic(const uint8_t* data) {
    if (data[0]!='d'||data[1]!='e'||data[2]!='x'||data[3]!='\n') return false;
    if (data[4]!='0'||data[5]!='3') return false;
    if (data[6]!='0'&&data[6]!='5'&&data[6]!='7'&&data[6]!='9') return false;
    if (data[7]!='\0') return false;
    return true;
}

static inline uint32_t readU32(const uint8_t* p); // 前向声明(定义在下方)

// ==================== 增强: 准结构识别 + 头重建(root 版) ====================
// 某些加固会清零 dex 前 32 字节(magic+checksum+signature), 导致:
//   - 基于 "dex\n" magic 的扫描全部落空
//   - /proc/<pid>/mem 读到的是一段"无头"数据
// 但 0x20 之后的 header 字段(file_size/header_size/endian_tag/data_off/各 section)
// 仍然保留 —— 通过结构不变量定位, 再用 magic/Adler32/SHA-1 重建头部即可完整还原。

static bool isLikelyDexStructure(const uint8_t* p, size_t avail_len) {
    if (p == nullptr || avail_len < 0x70 + 4) return false;
    uint32_t header_size=readU32(p+0x24), endian_tag=readU32(p+0x28);
    uint32_t data_off=readU32(p+0x6C), data_size=readU32(p+0x68);
    uint32_t file_size=readU32(p+0x20);
    if (header_size!=0x70) return false;
    if (endian_tag!=0x12345678) return false;
    if (data_off<0x70 || data_size==0) return false;
    if (data_size>MAX_DEX_SIZE) return false;
    if (file_size>0 && file_size<0x70) return false;
    uint64_t rough=(uint64_t)data_off+data_size;
    if (file_size>0 && rough>(uint64_t)file_size+0x100) return false;
    if (rough>avail_len+0x100000) return false;
    return true;
}

static size_t calculateDexSizeByHeader(const uint8_t* base) {
    if (base==nullptr) return 0;
    uint32_t data_off=readU32(base+0x6C), data_size=readU32(base+0x68);
    uint32_t file_size=readU32(base+0x20);
    uint64_t data_end=(uint64_t)data_off+data_size;
    if (data_size>0 && data_off>=0x70 && data_end<=MAX_DEX_SIZE) return (size_t)data_end;
    if (file_size>0 && file_size>=0x70 && file_size<=MAX_DEX_SIZE) return file_size;
    return 0;
}

static uint32_t computeAdler32(const uint8_t* data, size_t len) {
    const uint32_t MOD_ADLER=65521;
    uint32_t a=1,b=0;
    size_t i=0;
    while (i<len) {
        size_t block=len-i; if (block>5552) block=5552;
        for (size_t k=0;k<block;k++){ a+=data[i+k]; b+=a; }
        a%=MOD_ADLER; b%=MOD_ADLER;
        i+=block;
    }
    return (b<<16)|a;
}

// 最小 SHA-1(FIPS 180-1)
typedef struct { uint32_t h[5]; uint64_t len; uint8_t buf[64]; size_t buflen; } Sha1Ctx;
static void sha1Init(Sha1Ctx* c){ c->h[0]=0x67452301;c->h[1]=0xEFCDAB89;c->h[2]=0x98BADCFE;c->h[3]=0x10325476;c->h[4]=0xC3D2E1F0;c->len=0;c->buflen=0; }
static uint32_t sha1Rol(uint32_t v,int n){ return (v<<n)|(v>>(32-n)); }
static void sha1Block(Sha1Ctx* c,const uint8_t* p){
    uint32_t w[80];
    for(int i=0;i<16;i++) w[i]=((uint32_t)p[i*4]<<24)|((uint32_t)p[i*4+1]<<16)|((uint32_t)p[i*4+2]<<8)|p[i*4+3];
    for(int i=16;i<80;i++) w[i]=sha1Rol(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);
    uint32_t a=c->h[0],b=c->h[1],cc=c->h[2],d=c->h[3],e=c->h[4];
    for(int i=0;i<80;i++){
        uint32_t f,k;
        if(i<20){f=(b&cc)|(~b&d);k=0x5A827999;}
        else if(i<40){f=b^cc^d;k=0x6ED9EBA1;}
        else if(i<60){f=(b&cc)|(b&d)|(cc&d);k=0x8F1BBCDC;}
        else{f=b^cc^d;k=0xCA62C1D6;}
        uint32_t tmp=sha1Rol(a,5)+f+e+k+w[i];
        e=d;d=cc;cc=sha1Rol(b,30);b=a;a=tmp;
    }
    c->h[0]+=a;c->h[1]+=b;c->h[2]+=cc;c->h[3]+=d;c->h[4]+=e;
}
static void sha1Update(Sha1Ctx* c,const uint8_t* data,size_t len){
    c->len+=len;
    if(c->buflen){
        size_t need=64-c->buflen,take=len<need?len:need;
        memcpy(c->buf+c->buflen,data,take);c->buflen+=take;data+=take;len-=take;
        if(c->buflen==64){sha1Block(c,c->buf);c->buflen=0;}
    }
    while(len>=64){sha1Block(c,data);data+=64;len-=64;}
    if(len){memcpy(c->buf,data,len);c->buflen=len;}
}
static void sha1Final(Sha1Ctx* c,uint8_t out[20]){
    uint64_t bitlen=c->len*8;
    uint8_t pad=0x80;sha1Update(c,&pad,1);
    uint8_t zero=0;while(c->buflen!=56)sha1Update(c,&zero,1);
    uint8_t lenb[8];for(int i=0;i<8;i++)lenb[i]=(uint8_t)(bitlen>>(56-i*8));
    sha1Update(c,lenb,8);
    for(int i=0;i<5;i++){out[i*4]=(uint8_t)(c->h[i]>>24);out[i*4+1]=(uint8_t)(c->h[i]>>16);out[i*4+2]=(uint8_t)(c->h[i]>>8);out[i*4+3]=(uint8_t)c->h[i];}
}

// 原地重建 dex 前 32 字节。p 指向 dex 基址, 0x20 之后字段必须完好。
static size_t fixDexHeaderInPlace(uint8_t* p, size_t avail_len) {
    if (p==nullptr || avail_len<0x70+4) return 0;
    if (!isLikelyDexStructure(p,avail_len)) return 0;
    size_t size=calculateDexSizeByHeader(p);
    if (size==0 || size>avail_len || size<MIN_VALID_DEX_SIZE) return 0;
    // 尝试保留版本字节(可能残留 0x30 0x33 xx 0x00); 否则默认 039
    uint8_t magic[8]={'d','e','x','\n','0','3','9','\0'};
    if (avail_len>=8 && p[4]=='0' && p[5]=='3' &&
        (p[6]=='5'||p[6]=='7'||p[6]=='8'||p[6]=='9') && p[7]=='\0') {
        memcpy(magic,p,8);
    }
    memcpy(p,magic,8);
    // signature 先于 checksum: checksum 覆盖 0x0C..end(含 signature)
    Sha1Ctx ctx;sha1Init(&ctx);sha1Update(&ctx,p+32,size-32);
    uint8_t sig[20];sha1Final(&ctx,sig);
    memcpy(p+12,sig,20);
    uint32_t adler=computeAdler32(p+12,size-12);
    p[8]=(uint8_t)adler;p[9]=(uint8_t)(adler>>8);p[10]=(uint8_t)(adler>>16);p[11]=(uint8_t)(adler>>24);
    return size;
}

static inline uint32_t readU32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1]<<8) | ((uint32_t)p[2]<<16) | ((uint32_t)p[3]<<24);
}

static size_t calculateDexSize(const uint8_t* header_start, int mem_fd, uint64_t base_offset) {
    uint32_t data_off=readU32(header_start+0x6C), data_size=readU32(header_start+0x68);
    uint64_t data_end=(uint64_t)data_off+data_size;
    if (data_size>0 && data_off>=DEX_HEADER_SIZE && data_end<=MAX_DEX_SIZE)
        return (size_t)data_end;
    uint32_t file_size=readU32(header_start+0x20);
    if (file_size>0 && file_size>=DEX_HEADER_SIZE && file_size<=MAX_DEX_SIZE)
        return file_size;
    uint32_t map_off=readU32(header_start+0x34);
    if (map_off>DEX_HEADER_SIZE && map_off<MAX_DEX_SIZE && mem_fd>=0) {
        uint8_t mapBuf[4096];
        uint64_t mapAbsOff=base_offset+map_off;
        ssize_t n=pread(mem_fd,mapBuf,sizeof(mapBuf),(off_t)mapAbsOff);
        if (n>=4) {
            uint32_t mapSize=readU32(mapBuf);
            if (mapSize>0 && mapSize<65536) {
                size_t mapDataNeeded=4+mapSize*12;
                if (mapDataNeeded<=n) {
                    uint64_t maxEnd=DEX_HEADER_SIZE;
                    for (uint32_t i=0;i<mapSize;i++) {
                        size_t itemOff=4+i*12;
                        uint32_t itemSize=readU32(mapBuf+itemOff+4);
                        uint32_t itemOffset=readU32(mapBuf+itemOff+8);
                        uint64_t itemEnd=(uint64_t)itemOffset+itemSize;
                        if (itemEnd>maxEnd) maxEnd=itemEnd;
                    }
                    if (maxEnd>DEX_HEADER_SIZE && maxEnd<=MAX_DEX_SIZE) return maxEnd;
                }
            }
        }
    }
    return 0;
}

static bool isValidDexHeader(const uint8_t* header_start) {
    if (readU32(header_start+0x24)!=DEX_HEADER_SIZE) return false;
    if (readU32(header_start+0x28)!=0x12345678) return false;
    if (readU32(header_start+0x38)==0) return false;
    if (readU32(header_start+0x6C)<DEX_HEADER_SIZE) return false;
    if (readU32(header_start+0x68)==0) return false;
    if (readU32(header_start+0x34)<DEX_HEADER_SIZE) return false;
    uint32_t file_size=readU32(header_start+0x20);
    uint32_t d_off=readU32(header_start+0x6C), d_size=readU32(header_start+0x68);
    uint64_t data_end=(uint64_t)d_off+d_size;
    uint64_t rough_size=(data_end>file_size)?data_end:file_size;
    if (rough_size>MAX_DEX_SIZE) return false;
    if (rough_size<MIN_VALID_DEX_SIZE) return false;
    return true;
}

static bool verifyDexChecksum(const uint8_t* dex_data, size_t size) {
    if (size<=12) return false;
    uint32_t stored=readU32(dex_data+0x08);
    const uint32_t MOD_ADLER=65521;
    uint32_t a=1,b=0;
    for (size_t i=12;i<size;i++) { a=(a+dex_data[i])%MOD_ADLER; b=(b+a)%MOD_ADLER; }
    return ((b<<16)|a)==stored;
}

static int collectRegions(int pid, MemRegion* regions, int maxRegions) {
    char mapsPath[64];
    snprintf(mapsPath,sizeof(mapsPath),"/proc/%d/maps",pid);
    FILE* fp=fopen(mapsPath,"r");
    if (!fp) { log_info("ERROR: Cannot open %s: %s",mapsPath,strerror(errno)); return 0; }
    int count=0; char line[MAX_LINE];
    while (fgets(line,sizeof(line),fp) && count<maxRegions) {
        unsigned long long start,end,offset,inode;
        char perms[5],dev[8],pathname[512]={0};
        int parsed=sscanf(line,"%llx-%llx %4s %llx %7s %llu %511[^\n]",
            &start,&end,perms,&offset,dev,&inode,pathname);
        if (parsed<5) continue;
        if (perms[0]!='r') continue;
        if (parsed>=7 && pathname[0] && shouldExcludeRegion(pathname)) continue;
        if (end-start<DEX_HEADER_SIZE) continue;
        regions[count].start=(uint64_t)start; regions[count].end=(uint64_t)end;
        regions[count].readable=(perms[0]=='r'); regions[count].executable=(perms[2]=='x');
        if (parsed>=7) {
            size_t plen=strlen(pathname);
            if (plen>=sizeof(regions[count].pathname)) plen=sizeof(regions[count].pathname)-1;
            memcpy(regions[count].pathname,pathname,plen);
            regions[count].pathname[plen]='\0';
        } else regions[count].pathname[0]='\0';
        count++;
    }
    fclose(fp);
    return count;
}

static bool writeDataToFile(const uint8_t* data, size_t size, const char* path) {
    if (!data||!size||!path) return false;
    FILE* fp=fopen(path,"wb");
    if (!fp) { log_info("ERROR: Cannot create %s: %s",path,strerror(errno)); return false; }
    size_t written=fwrite(data,1,size,fp);
    fclose(fp);
    return written==size;
}

struct ProcessedAddr { uint64_t addr; };

static bool isProcessed(uint64_t addr, ProcessedAddr* processed, int processCount) {
    for (int i=0;i<processCount;i++) if (processed[i].addr==addr) return true;
    return false;
}
static void addProcessed(uint64_t addr, ProcessedAddr* processed, int* processCount, int max) {
    if (*processCount<max) { processed[*processCount].addr=addr; (*processCount)++; }
}

static int scanBufferForDex(
    const uint8_t* buf, size_t buf_size, uint64_t buf_offset,
    uint64_t region_start, size_t region_size,
    int mem_fd, const char* output_dir, int* dex_count,
    ProcessedAddr* processed, int* process_count, int max_processed)
{
    int found=0;
    for (size_t i=0; i+DEX_HEADER_SIZE<=buf_size; i+=4) {
        if (isDexMagic(buf+i)) {
            uint64_t abs_addr=region_start+buf_offset+i;
            if (isProcessed(abs_addr,processed,*process_count)) continue;

            size_t dex_size=calculateDexSize(buf+i,mem_fd,abs_addr);
            if (dex_size==0) {
                size_t max_forward=MAX_DEX_SIZE;
                if (buf_offset+i+max_forward>region_size) max_forward=region_size-(buf_offset+i);
                size_t search_end=(buf_offset+i+max_forward<=buf_size)?(i+max_forward):buf_size;
                bool found_next=false;
                for (size_t j=i+4; j+4<=search_end; j+=4)
                    if (isDexMagic(buf+j)) { dex_size=j-i; found_next=true; break; }
                if (!found_next) {
                    size_t remaining=region_size-(buf_offset+i);
                    dex_size=(remaining>MAX_DEX_SIZE)?MAX_DEX_SIZE:remaining;
                }
            }
            if (dex_size<DEX_HEADER_SIZE||dex_size>MAX_DEX_SIZE) continue;
            if (buf_offset+i+dex_size>region_size) continue;
            if (!isValidDexHeader(buf+i)) continue;
            if (dex_size<MIN_VALID_DEX_SIZE) continue;

            addProcessed(abs_addr,processed,process_count,max_processed);

            uint8_t* dex_data=(uint8_t*)malloc(dex_size);
            if (!dex_data) { log_info("ERROR: malloc(%zu) failed",dex_size); continue; }
            ssize_t bytes_read=pread(mem_fd,dex_data,dex_size,(off_t)abs_addr);
            if (bytes_read<=0||(size_t)bytes_read<DEX_HEADER_SIZE) { free(dex_data); continue; }
            if (!isDexMagic(dex_data)) { free(dex_data); continue; }
            if (!verifyDexChecksum(dex_data,(size_t)bytes_read)) { free(dex_data); continue; }

            char output_path[1024];
            snprintf(output_path,sizeof(output_path),"%s/rootdump_%d.dex",output_dir,(*dex_count)++);
            size_t actual_size=(size_t)bytes_read;
            if (writeDataToFile(dex_data,actual_size,output_path)) {
                found++;
                log_info("Dumped: %s (0x%llx, %zu bytes)",output_path,
                         (unsigned long long)abs_addr,actual_size);
            } else log_info("ERROR: Failed to write %s",output_path);
            free(dex_data);
            if (dex_size>DEX_HEADER_SIZE) { i+=(dex_size-DEX_HEADER_SIZE); i&=~3ULL; }
        } else {
            // ============ 增强: 准结构匹配(magic 被壳清零) ============
            // 壳在 dex 解密后清零前 32 字节, 导致 isDexMagic 永久失配。
            // 用 header 结构不变量(0x24==0x70, 0x28==0x12345678, 0x6C>=0x70)
            // 定位"无头 dex", 读出后本地重建头部。
            // 注意 avail 必须用"区域剩余"而非"块内剩余", 否则大 dex 被误拒。
            size_t region_remaining = region_size - (buf_offset + i);
            if (!isLikelyDexStructure(buf+i, region_remaining)) continue;
            size_t dex_size = calculateDexSizeByHeader(buf+i);
            if (dex_size==0 || dex_size>MAX_DEX_SIZE || dex_size<MIN_VALID_DEX_SIZE) continue;
            // 与 magic 分支一致: 直接按绝对地址 pread 完整 dex(跨块安全, 不依赖 scan_buf)
            if (buf_offset+i+dex_size>region_size) continue;
            uint64_t abs_addr=region_start+buf_offset+i;
            if (isProcessed(abs_addr,processed,*process_count)) continue;
            uint8_t* dex_data=(uint8_t*)malloc(dex_size);
            if (!dex_data) continue;
            ssize_t bytes_read=pread(mem_fd,dex_data,dex_size,(off_t)abs_addr);
            if (bytes_read<=0 || (size_t)bytes_read<MIN_VALID_DEX_SIZE) { free(dex_data); continue; }
            if (isDexMagic(dex_data)) { free(dex_data); continue; } // 已在上方 magic 分支处理
            size_t fixed = fixDexHeaderInPlace(dex_data,(size_t)bytes_read);
            if (fixed==0) { free(dex_data); continue; }
            addProcessed(abs_addr,processed,process_count,max_processed);
            char output_path[1024];
            snprintf(output_path,sizeof(output_path),"%s/rootdump_fix_%d.dex",output_dir,(*dex_count)++);
            if (writeDataToFile(dex_data,fixed,output_path)) {
                found++;
                log_info("Dumped(header-rebuilt): %s (0x%llx, %zu -> %zu bytes)",
                         output_path,(unsigned long long)abs_addr,dex_size,fixed);
            } else log_info("ERROR: Failed to write %s",output_path);
            free(dex_data);
            if (dex_size>DEX_HEADER_SIZE) { i+=(dex_size-DEX_HEADER_SIZE); i&=~3ULL; }
        }
    }
    return found;
}

int main(int argc, char* argv[]) {
    if (argc<3) {
        fprintf(stderr,"Usage: rootdump <pid> <output_dir>\n");
        return 1;
    }
    int pid=atoi(argv[1]);
    const char* output_dir=argv[2];
    if (pid<=0) { log_info("ERROR: Invalid PID"); return 1; }

    mkdir(output_dir,0777);

    char proc_path[64];
    snprintf(proc_path,sizeof(proc_path),"/proc/%d",pid);
    struct stat st;
    if (stat(proc_path,&st)!=0) { log_info("ERROR: Process %d does not exist",pid); return 1; }

    log_info("rootdump: scanning process %d, output to %s",pid,output_dir);

    MemRegion* regions=(MemRegion*)malloc(sizeof(MemRegion)*MAX_REGIONS);
    if (!regions) { log_info("ERROR: malloc for regions failed"); return 1; }
    int regionCount=collectRegions(pid,regions,MAX_REGIONS);
    log_info("Found %d scannable memory regions",regionCount);

    char mem_path[64];
    snprintf(mem_path,sizeof(mem_path),"/proc/%d/mem",pid);
    int mem_fd=open(mem_path,O_RDONLY);
    if (mem_fd<0) {
        log_info("ERROR: Cannot open %s: %s",mem_path,strerror(errno));
        log_info("Root privileges are required to read /proc/<pid>/mem");
        free(regions); return 1;
    }

    int total_found=0, dex_count=0;
    static const int MAX_PROCESSED=10000;
    ProcessedAddr* processed=(ProcessedAddr*)malloc(sizeof(ProcessedAddr)*MAX_PROCESSED);
    int processCount=0;
    if (!processed) { log_info("ERROR: malloc for dedup set failed"); close(mem_fd); free(regions); return 1; }

    const size_t scan_buf_size=SCAN_CHUNK_SIZE+DEX_HEADER_SIZE;
    uint8_t* scan_buf=(uint8_t*)malloc(scan_buf_size);
    if (!scan_buf) { log_info("ERROR: malloc for scan buffer failed"); free(processed); close(mem_fd); free(regions); return 1; }

    for (int r=0; r<regionCount; r++) {
        uint64_t rs=regions[r].start;
        size_t rSize=regions[r].end-regions[r].start;
        if (rSize>0xFFFFFFFFULL) continue;
        for (uint64_t co=0; co<rSize; co+=SCAN_CHUNK_SIZE) {
            size_t cs=SCAN_CHUNK_SIZE;
            if (co+cs>rSize) cs=rSize-co;
            if (co>0) {
                uint64_t oo=co-DEX_HEADER_SIZE;
                size_t os=cs+DEX_HEADER_SIZE;
                if (oo+os>rSize) os=rSize-oo;
                if (os>scan_buf_size) os=scan_buf_size;
                ssize_t n=pread(mem_fd,scan_buf,os,(off_t)(rs+oo));
                if (n<=0) break;
                int found=scanBufferForDex(scan_buf,(size_t)n,oo,rs,rSize,mem_fd,output_dir,&dex_count,processed,&processCount,MAX_PROCESSED);
                total_found+=found;
            } else {
                ssize_t n=pread(mem_fd,scan_buf,cs,(off_t)(rs+co));
                if (n<=0) break;
                int found=scanBufferForDex(scan_buf,(size_t)n,co,rs,rSize,mem_fd,output_dir,&dex_count,processed,&processCount,MAX_PROCESSED);
                total_found+=found;
            }
        }
    }

    free(scan_buf); free(processed); close(mem_fd); free(regions);

    log_info("Scan complete: %d DEX files dumped to %s",total_found,output_dir);
    fprintf(stdout,"DUMP_COUNT: %d\n",total_found);
    return 0;
}
