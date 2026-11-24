export interface Change {
  ops: string[]
}

export class Document {
  private readonly lines: string[] = []

  insert(index: number, text: string): void {
    if (index < 0 || index > this.lines.length) {
      throw new RangeError(`줄 ${index} 은 범위 밖이다`)
    }
    this.lines.splice(index, 0, text)
  }

  render(): string {
    return this.lines.map((line, i) => `${i + 1}: ${line}`).join('\n')
  }
}

export function applyRemote(change: Change): number {
  // ops 가 없으면 여기서 TypeError 가 난다. 데모 화면(14)이 이 경로로 에러를 낸다.
  const count = change.ops.length
  const doc = new Document()
  change.ops.forEach((op, i) => doc.insert(i, op))
  return count + doc.render().length
}
