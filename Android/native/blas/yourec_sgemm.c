/* Row-major SGEMM for qwen-asr on Android: C = alpha * op(A) * op(B) + beta * C.
 * B is packed once per call into panels of 8 columns, transposed as needed, so
 * the inner loop reads both operands contiguously and vectorizes (NEON on
 * arm64). Rows of C are split across threads. Only row-major is supported,
 * which is all qwen-asr asks for. */
#include "cblas.h"
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define PANEL 8
#define MAX_THREADS 8

typedef struct {
    int M, N, K, transA, lda, ldc, first, last;
    float alpha, beta;
    const float *A, *packed;
    float *C;
} Job;

/* One row of A against every packed panel of B. */
static void rows(const Job *job) {
    const int K = job->K, N = job->N, panels = (N + PANEL - 1) / PANEL;
    float *row = job->transA ? (float *)malloc((size_t)K * sizeof(float)) : NULL;
    for (int m = job->first; m < job->last; m++) {
        const float *a = job->A + (size_t)m * job->lda;
        if (job->transA) { for (int k = 0; k < K; k++) row[k] = job->A[(size_t)k * job->lda + m]; a = row; }
        float *c = job->C + (size_t)m * job->ldc;
        for (int p = 0; p < panels; p++) {
            const float *b = job->packed + (size_t)p * K * PANEL;
            float sum[PANEL] = {0};
            for (int k = 0; k < K; k++) {
                const float x = a[k];
                const float *bk = b + (size_t)k * PANEL;
                for (int j = 0; j < PANEL; j++) sum[j] += x * bk[j];
            }
            const int n0 = p * PANEL, count = N - n0 < PANEL ? N - n0 : PANEL;
            for (int j = 0; j < count; j++)
                c[n0 + j] = job->alpha * sum[j] + (job->beta == 0.0f ? 0.0f : job->beta * c[n0 + j]);
        }
    }
    free(row);
}
static void *worker(void *arg) { rows((const Job *)arg); return NULL; }

void cblas_sgemm(enum CBLAS_ORDER order, enum CBLAS_TRANSPOSE transA, enum CBLAS_TRANSPOSE transB,
                 int M, int N, int K, float alpha, const float *A, int lda,
                 const float *B, int ldb, float beta, float *C, int ldc) {
    if (order != CblasRowMajor || M <= 0 || N <= 0) return;
    const int panels = (N + PANEL - 1) / PANEL;
    float *packed = (float *)calloc((size_t)panels * K * PANEL, sizeof(float));
    if (!packed) abort();
    /* packed[p][k][j] = op(B)[k][p * PANEL + j] */
    for (int p = 0; p < panels; p++) {
        float *dst = packed + (size_t)p * K * PANEL;
        for (int j = 0; j < PANEL && p * PANEL + j < N; j++) {
            const int n = p * PANEL + j;
            if (transB == CblasNoTrans) { for (int k = 0; k < K; k++) dst[(size_t)k * PANEL + j] = B[(size_t)k * ldb + n]; }
            else { const float *src = B + (size_t)n * ldb; for (int k = 0; k < K; k++) dst[(size_t)k * PANEL + j] = src[k]; }
        }
    }
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    int threads = cores < 1 ? 1 : cores > MAX_THREADS ? MAX_THREADS : (int)cores;
    if ((long)M * N * K < 1L << 18) threads = 1;
    if (threads > M) threads = M;
    Job jobs[MAX_THREADS];
    pthread_t ids[MAX_THREADS];
    for (int t = 0; t < threads; t++) {
        jobs[t] = (Job){ M, N, K, transA != CblasNoTrans, lda, ldc, (int)((long)M * t / threads), (int)((long)M * (t + 1) / threads), alpha, beta, A, packed, C };
        if (t > 0 && pthread_create(&ids[t], NULL, worker, &jobs[t]) != 0) { rows(&jobs[t]); ids[t] = 0; }
    }
    rows(&jobs[0]);
    for (int t = 1; t < threads; t++) if (ids[t]) pthread_join(ids[t], NULL);
    free(packed);
}
