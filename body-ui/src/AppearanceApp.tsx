import { ChangeEvent, useEffect, useMemo, useState } from 'react'

type Session = { rpName?: string; model?: 'classic' | 'slim'; textureUrl?: string; success?: boolean; message?: string }

const params = new URLSearchParams(window.location.search)
const token = params.get('token') ?? ''
const apiUrl = (params.get('apiUrl') ?? 'https://api.eclipse-roleplay.online').replace(/\/+$/, '')
const username = params.get('username') ?? 'Персонаж'

export default function AppearanceApp() {
  const [name, setName] = useState(username)
  const [model, setModel] = useState<'classic' | 'slim'>('classic')
  const [preview, setPreview] = useState<string>('')
  const [fileData, setFileData] = useState<string>('')
  const [fileName, setFileName] = useState('Файл ещё не выбран')
  const [notice, setNotice] = useState('Проверяем сеанс изменения внешности…')
  const [noticeType, setNoticeType] = useState<'neutral' | 'ok' | 'error'>('neutral')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    const stopBrowserBehaviour = (event: Event) => event.preventDefault()
    document.addEventListener('contextmenu', stopBrowserBehaviour)
    document.addEventListener('dragstart', stopBrowserBehaviour)
    document.addEventListener('selectstart', stopBrowserBehaviour)
    return () => {
      document.removeEventListener('contextmenu', stopBrowserBehaviour)
      document.removeEventListener('dragstart', stopBrowserBehaviour)
      document.removeEventListener('selectstart', stopBrowserBehaviour)
    }
  }, [])

  useEffect(() => {
    let disposed = false
    async function load() {
      if (!token) {
        setNotice('Нет действующего сеанса. Выполните /skin ещё раз.')
        setNoticeType('error')
        return
      }
      try {
        const response = await fetch(`${apiUrl}/api/appearance/edit-session?token=${encodeURIComponent(token)}`, { cache: 'no-store' })
        const session = await response.json() as Session
        if (!response.ok || !session.success) throw new Error(session.message || 'Сеанс недействителен.')
        if (disposed) return
        setName(session.rpName || username)
        setModel(session.model === 'slim' ? 'slim' : 'classic')
        setPreview(session.textureUrl || '')
        setNotice('Выберите PNG-облик для проверки.')
        setNoticeType('neutral')
      } catch (error) {
        if (!disposed) {
          setNotice(error instanceof Error ? error.message : 'Не удалось открыть мастерскую.')
          setNoticeType('error')
        }
      }
    }
    void load()
    return () => { disposed = true }
  }, [])

  const portraitStyle = useMemo(() => preview ? { backgroundImage: `url("${preview.replace(/"/g, '\\"')}")` } : undefined, [preview])

  const chooseFile = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    setFileData('')
    if (!file) return
    if (file.type !== 'image/png' || file.size > 512 * 1024) {
      setFileName('Файл отклонён')
      setNotice('Нужен PNG-файл размером до 512 КБ.')
      setNoticeType('error')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const data = String(reader.result || '')
      const image = new Image()
      image.onload = () => {
        if (image.width !== 64 || (image.height !== 32 && image.height !== 64)) {
          setFileName('Файл отклонён')
          setNotice('Размер скина должен быть 64×64 или 64×32 пикселя.')
          setNoticeType('error')
          return
        }
        setPreview(data)
        setFileData(data)
        setFileName(file.name)
        setNotice('Облик проверен. Его можно подтвердить.')
        setNoticeType('ok')
      }
      image.onerror = () => {
        setNotice('Этот PNG не удалось прочитать.')
        setNoticeType('error')
      }
      image.src = data
    }
    reader.readAsDataURL(file)
  }

  const save = async () => {
    if (!fileData || saving) return
    setSaving(true)
    setNotice('Сохраняем внешний облик…')
    setNoticeType('neutral')
    try {
      const response = await fetch(`${apiUrl}/api/appearance/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, appearanceData: fileData, appearanceModel: model }),
      })
      const result = await response.json() as Session
      if (!response.ok || !result.success) throw new Error(result.message || 'Не удалось сохранить облик.')
      setNotice('Облик применён. Нажмите Esc, чтобы вернуться в игру.')
      setNoticeType('ok')
      setFileName('Облик сохранён')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Ошибка сети. Повторите попытку.')
      setNoticeType('error')
    } finally {
      setSaving(false)
    }
  }

  return <main className="appearance-root">
    <section className="dossier enter" aria-label="Мастерская внешности Eclipse">
      <header className="dossier__header">
        <div className="sigil" aria-hidden="true">E</div>
        <div><p className="eyebrow">ECLIPSE ROLEPLAY <span>•</span> ЛИЧНОЕ ДЕЛО</p><h1>Внешность</h1></div>
        <div className="live"><i />СЕАНС ИЗМЕНЕНИЯ</div>
      </header>

      <section className="identity">
        <div className={`portrait ${preview ? 'portrait--skin' : ''}`} style={portraitStyle}><span /></div>
        <div className="identity__copy"><p className="eyebrow">ПЕРСОНАЖ</p><strong>{name}</strong><span>Новый облик увидят все игроки поблизости.</span></div>
      </section>

      <section className="select-skin">
        <div className="section-row"><div><p className="eyebrow">НОВЫЙ ОБЛИК</p><b>{fileName}</b></div><small>PNG&nbsp; 64×64 / 64×32&nbsp; • &nbsp;до 512 КБ</small></div>
        <label className="drop-zone"><input type="file" accept="image/png" onChange={chooseFile}/><span className="plus">+</span><span><b>Выбрать PNG-облик</b><em>Откроется системный выбор файла</em></span></label>
      </section>

      <section className="model-row"><p className="eyebrow">КРОЙ РУКАВОВ</p><div className="model-options">
        <button className={model === 'classic' ? 'selected' : ''} onClick={() => setModel('classic')}>Обычная модель <small>4 px</small></button>
        <button className={model === 'slim' ? 'selected' : ''} onClick={() => setModel('slim')}>Стройная модель <small>3 px</small></button>
      </div></section>

      <footer className="dossier__footer"><p className={`notice notice--${noticeType}`}>{notice}</p><div className="actions"><button className="save" disabled={!fileData || saving} onClick={save}>{saving ? 'СОХРАНЕНИЕ…' : 'ПОДТВЕРДИТЬ ОБЛИК'}<i>↗</i></button><span>ESC — закрыть</span></div></footer>
    </section>
  </main>
}
