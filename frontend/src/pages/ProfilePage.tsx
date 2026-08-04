import { useRef, useState } from 'react'
import { useAuthStore } from '../store/auth'
import { updateProfile, changePassword, uploadFile } from '../api/user'

const roleLabel: Record<string, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  ADMIN: '管理员',
}

export default function ProfilePage() {
  const { user, updateUser } = useAuthStore()
  const fileInputRef = useRef<HTMLInputElement>(null)

  // 资料表单
  const [nickname, setNickname] = useState(user?.nickname ?? '')
  const [avatarObjectName, setAvatarObjectName] = useState<string | null>(null) // MinIO objectName
  const [avatarPreview, setAvatarPreview] = useState<string | null>(user?.avatar ?? null) // 预览 URL
  const [bio, setBio] = useState(user?.bio ?? '')
  const [profileMsg, setProfileMsg] = useState('')
  const [profileLoading, setProfileLoading] = useState(false)
  const [uploading, setUploading] = useState(false)

  // 密码表单
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [pwdMsg, setPwdMsg] = useState('')
  const [pwdLoading, setPwdLoading] = useState(false)

  if (!user) return null

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    // 文件大小限制 5MB
    if (file.size > 5 * 1024 * 1024) {
      setProfileMsg('头像文件不能超过 5MB')
      return
    }
    // 类型限制
    if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)) {
      setProfileMsg('仅支持 JPG / PNG / GIF / WebP 格式')
      return
    }

    setProfileMsg('')
    setUploading(true)
    try {
      // 先显示本地预览
      setAvatarPreview(URL.createObjectURL(file))

      const res = await uploadFile(file)
      setAvatarObjectName(res.data.objectName)
      // 用返回的预签名 URL 替换本地 blob URL
      setAvatarPreview(res.data.url)
      setProfileMsg('头像已选择，点击"保存资料"生效')
    } catch (err) {
      setProfileMsg(err instanceof Error ? err.message : '上传失败')
      setAvatarPreview(null)
      setAvatarObjectName(null)
    } finally {
      setUploading(false)
    }
  }

  const handleClearAvatar = () => {
    setAvatarObjectName('') // 空串 = 清空头像
    setAvatarPreview(null)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const handleProfile = async (e: React.FormEvent) => {
    e.preventDefault()
    setProfileMsg('')
    setProfileLoading(true)
    try {
      const payload: Record<string, string> = { nickname }
      if (avatarObjectName !== null) {
        payload.avatar = avatarObjectName // '' = 清空，其他 = MinIO objectName
      }
      if (bio !== (user.bio ?? '')) {
        payload.bio = bio
      }
      const res = await updateProfile(payload)
      updateUser(res.data)
      setProfileMsg('资料已更新')
      // 重置 file input
      if (fileInputRef.current) fileInputRef.current.value = ''
      setAvatarObjectName(null)
    } catch (err) {
      setProfileMsg(err instanceof Error ? err.message : '更新失败')
    } finally {
      setProfileLoading(false)
    }
  }

  const handlePassword = async (e: React.FormEvent) => {
    e.preventDefault()
    setPwdMsg('')
    if (newPassword !== confirmPassword) {
      setPwdMsg('两次输入的新密码不一致')
      return
    }
    setPwdLoading(true)
    try {
      await changePassword({ oldPassword, newPassword })
      setPwdMsg('密码已修改')
      setOldPassword('')
      setNewPassword('')
      setConfirmPassword('')
    } catch (err) {
      setPwdMsg(err instanceof Error ? err.message : '修改失败')
    } finally {
      setPwdLoading(false)
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-slate-800">个人中心</h1>
        <p className="text-sm text-slate-500 mt-1">
          {user.username} · {roleLabel[user.role]}
        </p>
      </div>

      {/* 修改资料 */}
      <form onSubmit={handleProfile} className="bg-white rounded-2xl shadow-sm p-6 space-y-4">
        <h2 className="text-base font-semibold text-slate-700">基本资料</h2>
        {profileMsg && (
          <div
            className={`text-sm rounded-lg px-3 py-2 ${
              profileMsg.includes('已更新') || profileMsg.includes('已选择')
                ? 'text-green-600 bg-green-50'
                : 'text-red-600 bg-red-50'
            }`}
          >
            {profileMsg}
          </div>
        )}
        <div className="space-y-3">
          {/* 头像上传 */}
          <div>
            <span className="text-sm text-slate-600">头像（可选）</span>
            <div className="mt-2 flex items-center gap-4">
              {/* 预览 */}
              {avatarPreview ? (
                <img
                  src={avatarPreview}
                  alt="avatar"
                  className="w-16 h-16 rounded-full object-cover border-2 border-slate-200"
                  onError={(e) => ((e.target as HTMLImageElement).style.display = 'none')}
                />
              ) : (
                <div className="w-16 h-16 rounded-full bg-slate-100 border-2 border-dashed border-slate-300 flex items-center justify-center text-slate-400 text-xs">
                  暂无
                </div>
              )}
              <div className="flex flex-col gap-2">
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png,image/gif,image/webp"
                  onChange={handleFileChange}
                  className="hidden"
                />
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={uploading}
                    className="px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-300 text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                  >
                    {uploading ? '上传中...' : '选择图片'}
                  </button>
                  {avatarPreview && (
                    <button
                      type="button"
                      onClick={handleClearAvatar}
                      className="px-3 py-1.5 text-xs font-medium rounded-lg border border-red-200 text-red-500 hover:bg-red-50"
                    >
                      移除
                    </button>
                  )}
                </div>
                <span className="text-xs text-slate-400">支持 JPG/PNG/GIF/WebP，最大 5MB</span>
              </div>
            </div>
          </div>

          <label className="block">
            <span className="text-sm text-slate-600">昵称</span>
            <input
              className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              maxLength={20}
              required
            />
          </label>
          <label className="block">
            <span className="text-sm text-slate-600">个人简介（可选）</span>
            <textarea
              className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              rows={3}
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              maxLength={255}
              placeholder="一句话介绍自己"
            />
          </label>
        </div>
        <button
          type="submit"
          disabled={profileLoading}
          className="bg-blue-600 text-white rounded-lg px-4 py-2 text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
        >
          {profileLoading ? '保存中...' : '保存资料'}
        </button>
      </form>

      {/* 修改密码 */}
      <form onSubmit={handlePassword} className="bg-white rounded-2xl shadow-sm p-6 space-y-4">
        <h2 className="text-base font-semibold text-slate-700">修改密码</h2>
        {pwdMsg && (
          <div
            className={`text-sm rounded-lg px-3 py-2 ${
              pwdMsg.includes('已修改') ? 'text-green-600 bg-green-50' : 'text-red-600 bg-red-50'
            }`}
          >
            {pwdMsg}
          </div>
        )}
        <div className="space-y-3">
          <label className="block">
            <span className="text-sm text-slate-600">原密码</span>
            <input
              type="password"
              className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={oldPassword}
              onChange={(e) => setOldPassword(e.target.value)}
              required
            />
          </label>
          <label className="block">
            <span className="text-sm text-slate-600">新密码（6-32 位）</span>
            <input
              type="password"
              className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              minLength={6}
              maxLength={32}
              required
            />
          </label>
          <label className="block">
            <span className="text-sm text-slate-600">确认新密码</span>
            <input
              type="password"
              className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              minLength={6}
              maxLength={32}
              required
            />
          </label>
        </div>
        <button
          type="submit"
          disabled={pwdLoading}
          className="bg-blue-600 text-white rounded-lg px-4 py-2 text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
        >
          {pwdLoading ? '修改中...' : '修改密码'}
        </button>
      </form>
    </div>
  )
}
