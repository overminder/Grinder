#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef long (*gentry_t)(long);

int main(int argc, char **argv) {
  void *lib = dlopen(argv[1], RTLD_LAZY);
  gentry_t fun = dlsym(lib, "grinderEntry");
  printf("%ld\n", fun(atoi(argv[2])));
}
