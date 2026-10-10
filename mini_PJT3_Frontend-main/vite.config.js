import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // 일반 API 요청을 위한 설정
      '/api': {
        target: 'http://localhost:8090', // LOCAL DEV: 8080이 다른 컨테이너에 점유돼 있어 8090으로 변경
        changeOrigin: true, // 대상 서버의 호스트 헤더를 target 주소로 변경
        secure: false,      // SSL 인증서 검증 무시 (로컬 테스트용)
      },
      // 구글 소셜 로그인 인증을 위한 설정 추가
      '/oauth2': {
        target: 'http://localhost:8090',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})