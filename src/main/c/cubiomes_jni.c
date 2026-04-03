/*
 * JNI wrapper around the cubiomes library.
 *
 * Two separate Generator instances are used to avoid cross-thread races:
 *   g_struct  — written by nativeInit()       / read by findStructures()
 *               Both called from the MC server-thread execute() callback.
 *   g_biome   — written by nativeInitBiome()  / read by getBiomesGrid()
 *               Both called from the xaeroseedmap-biome-gen background thread.
 */

#include <jni.h>
#include <math.h>
#include <string.h>

#include "generator.h"
#include "finders.h"

#define MAX_RESULTS 512

/* -------------------------------------------------------------------------
 * Structure generator  (server thread)
 * ---------------------------------------------------------------------- */
static Generator g_struct;
static int       g_struct_init = 0;
static uint64_t  g_seed        = 0;

JNIEXPORT jboolean JNICALL
Java_dev_tggamesyt_xaeroseedmap_CubiomesLib_nativeInit(
        JNIEnv *env, jclass cls, jlong seed, jint dimension)
{
    g_seed = (uint64_t) seed;
    setupGenerator(&g_struct, MC_NEWEST, 0);
    applySeed(&g_struct, (int) dimension, g_seed);
    g_struct_init = 1;
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_dev_tggamesyt_xaeroseedmap_CubiomesLib_findStructures(
        JNIEnv *env, jclass cls,
        jint structType,
        jint minBX, jint minBZ, jint maxBX, jint maxBZ)
{
    jintArray empty = (*env)->NewIntArray(env, 0);
    if (!g_struct_init) return empty;

    StructureConfig config;
    if (!getStructureConfig((int) structType, MC_NEWEST, &config)) return empty;

    int regionSize = (int) config.regionSize;
    if (regionSize <= 0) return empty;

    int regionBlocks = regionSize * 16;
    int minRegX = (int) floor((double) minBX / regionBlocks) - 1;
    int maxRegX = (int) floor((double) maxBX / regionBlocks) + 1;
    int minRegZ = (int) floor((double) minBZ / regionBlocks) - 1;
    int maxRegZ = (int) floor((double) maxBZ / regionBlocks) + 1;

    jint results[MAX_RESULTS * 2];
    int  count = 0;

    for (int rx = minRegX; rx <= maxRegX && count < MAX_RESULTS; rx++) {
        for (int rz = minRegZ; rz <= maxRegZ && count < MAX_RESULTS; rz++) {
            Pos pos;
            if (!getStructurePos((int) structType, MC_NEWEST, g_seed, rx, rz, &pos))
                continue;

            int bx = pos.x * 16 + 8;
            int bz = pos.z * 16 + 8;
            if (bx < minBX || bx > maxBX || bz < minBZ || bz > maxBZ) continue;
            if (!isViableStructurePos((int) structType, &g_struct, bx, bz, 0)) continue;

            results[count * 2]     = bx;
            results[count * 2 + 1] = bz;
            count++;
        }
    }

    jintArray arr = (*env)->NewIntArray(env, count * 2);
    if (count > 0)
        (*env)->SetIntArrayRegion(env, arr, 0, count * 2, results);
    return arr;
}

/* -------------------------------------------------------------------------
 * Biome generator  (background biome-gen thread)
 * ---------------------------------------------------------------------- */
static Generator g_biome;
static int       g_biome_init = 0;

JNIEXPORT jboolean JNICALL
Java_dev_tggamesyt_xaeroseedmap_CubiomesLib_nativeInitBiome(
        JNIEnv *env, jclass cls, jlong seed, jint dimension)
{
    setupGenerator(&g_biome, MC_NEWEST, 0);
    applySeed(&g_biome, (int) dimension, (uint64_t) seed);
    g_biome_init = 1;
    return JNI_TRUE;
}

/*
 * getBiomesGrid — fills a cols×rows grid of biome IDs starting at
 * (minBX, minBZ) with (stepBX, stepBZ) block steps.
 * Coordinates are converted to biome-space (÷4) internally.
 * blockY is passed as a biome-space Y (use 16 for surface, i.e. block y=64).
 */
JNIEXPORT jintArray JNICALL
Java_dev_tggamesyt_xaeroseedmap_CubiomesLib_getBiomesGrid(
        JNIEnv *env, jclass cls,
        jint minBX, jint minBZ,
        jint stepBX, jint stepBZ,
        jint cols, jint rows,
        jint biomeY)
{
    jint total = cols * rows;
    jintArray arr = (*env)->NewIntArray(env, total);
    if (!g_biome_init) return arr;

    jint *buf = (*env)->GetIntArrayElements(env, arr, NULL);

    for (jint row = 0; row < rows; row++) {
        for (jint col = 0; col < cols; col++) {
            int bx = (int)minBX + col * (int)stepBX;
            int bz = (int)minBZ + row * (int)stepBZ;
            /* scale=4: coordinates are in 4-block (biome) units */
            buf[row * cols + col] = getBiomeAt(&g_biome, 4, bx >> 2, (int)biomeY, bz >> 2);
        }
    }

    (*env)->ReleaseIntArrayElements(env, arr, buf, 0);
    return arr;
}
