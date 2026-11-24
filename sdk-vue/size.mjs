// 번들 크기를 잰다. 목표는 압축 후 10KB 이하(11.3).
import { readFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'

const code = readFileSync('dist/errdeck-vue.js')
const gz = gzipSync(code, { level: 9 })
console.log(`errdeck-vue.js ${code.length} 바이트, gzip ${gz.length} 바이트`)
