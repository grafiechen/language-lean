/** 已存在的静态文件和页面导航由 Assets 直接处理；缺失资源不能回退成 HTML 并被长期缓存。 */
export default {
  async fetch(): Promise<Response> {
    return new Response('Not found', {
      status: 404,
      headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
    })
  },
}
