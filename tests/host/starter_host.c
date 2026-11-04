/* Loads build/libscripts.so of the starter project into a fake engine and drives it. */
#include <stdio.h>
#include <dlfcn.h>
#include <string.h>
#include <stdint.h>
/* 1 World  2 Hero(Body)  3 Jump(Button)  4 Coin(Body)  5 Score(Label) */
static const char *names[] = {"", "World", "Hero", "Jump", "Coin", "Score"};
static const char *types[] = {"", "Node2D", "Body", "Button", "Body", "Label"};
static double vy = 0; static int hero_tag = 1; static int freed4 = 0;
static void h_log(const char *m) { printf("[log] %s\n", m); }
static int64_t h_find(int64_t from, const char *p) {
	if (!strcmp(p, "/World/Jump")) return 3; if (!strcmp(p, "/World/Coin")) return 4; return 0; }
static int64_t h_parent(int64_t id) { return id > 1 ? 1 : 0; }
static const char *h_type(int64_t id) { return types[id]; }
static int64_t z1(int64_t a) { return 0; } static int64_t z2(int64_t a, int64_t b) { return 0; }
static int64_t h_add(int64_t p, const char *k, const char *n) { return 0; }
static int64_t h_spawn(const char *s, int64_t p) { return 0; }
static void h_free(int64_t id) { printf("[engine] free %s\n", names[id]); }
static int32_t h_alive(int64_t id) { return 1; }
static int32_t h_has(int64_t id, const char *p) { return !strcmp(p, "tweak:speed") && id == 2; }
static double h_getf(int64_t id, const char *p) { if (!strcmp(p, "tweak:speed")) return 111.0; if (!strcmp(p, "vy")) return vy; return 0; }
static const char *h_gets(int64_t id, const char *p) { return !strcmp(p, "name") ? names[id] : ""; }
static int32_t h_getb(int64_t id, const char *p) { return !strcmp(p, "onGround"); }
static void h_setf(int64_t id, const char *p, double v) { if (!strcmp(p, "vy")) { vy = v; printf("[engine] hero vy=%g\n", v); } }
static void h_sets(int64_t id, const char *p, const char *v) { printf("[engine] %s.%s = %s\n", names[id], p, v); }
static void h_setb(int64_t id, const char *p, int32_t v) {}
static void h_tag(int64_t id, const char *t, int32_t on) { printf("[engine] tag %s %s\n", names[id], t); }
static int32_t h_hastag(int64_t id, const char *t) { return id == 2 && !strcmp(t, "hero"); }
static int64_t h_tc(const char *t) { return 0; } static int64_t h_ta(const char *t, int64_t i) { return 0; }
static int32_t h_down(const char *a) { return !strcmp(a, "jump"); }
static double h_axis(const char *a, const char *b) { return 1.0; }
static int64_t h_tcount(void) { return 0; } static double h_tx(int64_t i) { return 0; }
static double h_sw(void) { return 1280; } static void h_sound(const char *f) {} static void h_goto(const char *f) {}
static void h_store(const char *k, const char *v) {} static const char *h_fetch(const char *k) { return ""; }
#define SET(name, fn) ((void(*)(void*))dlsym(h, "sn_set_extern_" name))((void*)fn)
int main(int argc, char **argv) {
	void *h = dlopen(argc > 1 ? argv[1] : "./libscripts.so", RTLD_NOW);
	if (!h) { printf("dlopen failed: %s\n", dlerror()); return 1; }
	const char *(*lasterr)(void) = dlsym(h, "sn_last_error");
	SET("nb_log", h_log); SET("nb_find", h_find); SET("nb_parent", h_parent); SET("nb_type", h_type);
	SET("nb_child_count", z1); SET("nb_child_at", z2); SET("nb_add", h_add); SET("nb_spawn", h_spawn);
	SET("nb_free", h_free); SET("nb_has", h_has); SET("nb_alive", h_alive); SET("nb_get_f", h_getf); SET("nb_get_s", h_gets);
	SET("nb_get_b", h_getb); SET("nb_set_f", h_setf); SET("nb_set_s", h_sets); SET("nb_set_b", h_setb);
	SET("nb_tag", h_tag); SET("nb_has_tag", h_hastag); SET("nb_tagged_count", h_tc); SET("nb_tagged_at", h_ta);
	SET("nb_down", h_down); SET("nb_axis", h_axis); SET("nb_touch_count", h_tcount); SET("nb_touch_x", h_tx);
	SET("nb_touch_y", h_tx); SET("nb_screen_w", h_sw); SET("nb_screen_h", h_sw); SET("nb_sound", h_sound);
	SET("nb_goto", h_goto); SET("nb_store", h_store); SET("nb_fetch", h_fetch);
	printf("init=%d\n", ((int(*)(void))dlsym(h, "sn_init"))());
	void (*attach)(int64_t, const char *) = dlsym(h, "sn_nbAttach");
	void (*ready)(int64_t) = dlsym(h, "sn_nbReady");
	void (*frame)(int64_t, double) = dlsym(h, "sn_nbFrame");
	void (*every)(int64_t, int64_t, double) = dlsym(h, "sn_nbEvery");
	void (*fire)(int64_t, const char *, double, double, int64_t) = dlsym(h, "sn_nbFire");
	void (*freed)(int64_t) = dlsym(h, "sn_nbFreed");
	attach(2, "Hero"); attach(4, "Coin"); attach(5, "Score");
	ready(5); ready(2); ready(4); printf("err=[%s]\n", lasterr());
	frame(2, 0.016);                      /* jump is down and onGround -> jump() -> push, shout jumped */
	fire(3, "tapped", 1, 2, 0);           /* Jump button tapped -> hero.jump() */
	fire(4, "collided", 0, 0, 2);         /* hero touches coin -> collected(10) -> Score; coin frees itself */
	fire(4, "collided", 0, 0, 5);         /* score touches coin: no tag -> nothing */
	every(5, 5000, 5.0);
	freed(4);
	fire(4, "collided", 0, 0, 2); printf("err=[%s]\n", lasterr());
	return 0;
}
