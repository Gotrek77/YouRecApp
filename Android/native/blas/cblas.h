/* The part of CBLAS that qwen-asr uses (ThirdParty/qwen-asr, built with
 * USE_BLAS): row-major single-precision GEMM. Android has no system BLAS;
 * yourec_sgemm.c implements this one call. */
#ifndef YOUREC_CBLAS_H
#define YOUREC_CBLAS_H
#ifdef __cplusplus
extern "C" {
#endif
enum CBLAS_ORDER { CblasRowMajor = 101, CblasColMajor = 102 };
enum CBLAS_TRANSPOSE { CblasNoTrans = 111, CblasTrans = 112, CblasConjTrans = 113 };
void cblas_sgemm(enum CBLAS_ORDER order, enum CBLAS_TRANSPOSE transA, enum CBLAS_TRANSPOSE transB,
                 int M, int N, int K, float alpha, const float *A, int lda,
                 const float *B, int ldb, float beta, float *C, int ldc);
#ifdef __cplusplus
}
#endif
#endif
