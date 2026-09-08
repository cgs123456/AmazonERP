import request from './auth'
import type { ApiResponse } from './types'

// 知识库文本块（对齐后端 KnowledgeChunk）
export interface KnowledgeChunk {
  docId: string
  filename: string
  chunkIndex: number
  content: string
  score?: number | null
}

// 文档入库结果（对齐后端 KnowledgeDocumentService#uploadDocument）
export interface KnowledgeUploadResult {
  docId: string
  filename: string
  chunks: number
  indexed: number
}

// 文档删除结果
export interface KnowledgeDeleteResult {
  docId: string
  deleted: number
}

// 上传文档并入库（后端 POST /ai/knowledge/documents，multipart，shopId 为 query 参数）
// 注意：禁止手动设置 Content-Type（浏览器按 FormData 自动生成带 boundary 的头；
// 手写 multipart/form-data 无 boundary，后端恒 400，文档永远入不了库）
export const uploadKnowledgeDocument = (shopId: number | string, file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request.post<void, ApiResponse<KnowledgeUploadResult>>('/ai/knowledge/documents', form, {
    params: { shopId }
  })
}

// 删除整篇文档（后端 DELETE /ai/knowledge/documents/{docId}?shopId=）
export const deleteKnowledgeDocument = (shopId: number | string, docId: string) => {
  return request.delete<void, ApiResponse<KnowledgeDeleteResult>>(
    `/ai/knowledge/documents/${encodeURIComponent(docId)}`,
    { params: { shopId } }
  )
}

// 检索验证（后端 GET /ai/knowledge/search?shopId=&query=&topN=）
export const searchKnowledgeBase = (shopId: number | string, query: string, topN = 5) => {
  return request.get<void, ApiResponse<KnowledgeChunk[]>>('/ai/knowledge/search', {
    params: { shopId, query, topN }
  })
}
