/* page_size.c — runtime page-size probe. Never assume 4096. */
#include "pocket_pkg.h"
#include <unistd.h>

int32_t pkg_probe_page_size(void)
{
    long ps = sysconf(_SC_PAGE_SIZE);
    return (int32_t)(ps > 0 ? ps : 0);
}
