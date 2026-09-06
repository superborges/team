export interface AuthStatus { mode: 'local' | 'sso'; localLoginEnabled: boolean; ssoConfigured: boolean }
export interface Me { id: string; employeeNo: string; name: string; departmentId: string; departmentName: string; roles: string[]; canManage: boolean }
export interface WorkItem { id: string; code: string; name: string; type: 'PROJECT' | 'NON_PROJECT' | 'IDLE'; ownerDepartmentId: string; approverName: string }
export interface Department { id: string; code: string; name: string }
export interface Catalog { workItems: WorkItem[]; departments: Department[] }
export type EntryKind = 'WORK' | 'TRAVEL' | 'IDLE';
export interface EntryInput { correctionRequestId?: string | null; id: string | null; workItemId: string; kind: EntryKind; hours: string; content: string; redReason: string }
export interface Entry extends EntryInput { action?: string; correctionRequestId?: string | null; disputeOpen?: boolean; approvalItemId?: string | null; canRequestCorrection?: boolean; id: string; revisionId: string; workItemName: string; minutes: number; state: string; editable: boolean }
export interface OnsiteInput { correctionRequestId?: string | null; id: string | null; workItemId: string; reason: string }
export interface Onsite extends OnsiteInput { action?: string; correctionRequestId?: string | null; disputeOpen?: boolean; approvalItemId?: string | null; canRequestCorrection?: boolean; id: string; revisionId: string; workItemName: string; state: string; editable: boolean }
export interface Day { stepMinutes?: number; redFlagMinutes?: number; dayLimitMinutes?: number; date: string; version: number; baseMinutes: number; leaveMinutes: number; requiredMinutes: number; isWorkday: boolean; enrolled: boolean; editable: boolean; periodStatus: string; entries: Entry[]; onsite: Onsite | null; totalMinutes: number; actualMinutes: number; idleMinutes: number; validation: { ready: boolean; errors: string[]; warnings: string[] } }
export interface Week { weekStart: string; days: Day[] }
export interface DayInput { expectedVersion: number; entries: EntryInput[]; onsite: OnsiteInput | null }
export interface CalendarDay { date: string; isWorkday: boolean; baseMinutes: number; note: string }
export type ImportDataset = 'PROJECT' | 'RATE' | 'LEAVE' | 'USER' | 'DEPARTMENT' | 'TRIP';
export interface ImportRow { rowNumber: number; data: Record<string, string>; valid: boolean; error: string | null; state: 'VALID' | 'INVALID' | 'APPLIED' | 'FAILED' }
export interface ImportBatchSummary { id: string; dataset: ImportDataset; state: 'PREVIEW' | 'APPLIED' | 'PARTIAL'; validCount: number; errorCount: number; appliedCount: number; createdAt: string; fileName: string }
export interface ImportBatch extends ImportBatchSummary { rows: ImportRow[] }
export interface SubmitItem { revisionId: string; recordId: string; workItemId: string; workItemName: string; kind?: string; hours?: string; approverId: string | null; approverName: string | null; routeReason: string }
export interface WeekPreview { weekStart: string; days: { date: string; workReady: boolean; onsiteReady: boolean; errors: string[]; warnings: string[]; workItems: SubmitItem[]; onsite: SubmitItem | null }[] }
export interface ResultItem { id: string | null; kind: 'TIME' | 'ONSITE' | 'DAY'; date: string; packageId: string | null; code: string | null; message: string }
export interface BatchResult { succeeded: ResultItem[]; failed: ResultItem[]; unchanged: ResultItem[] }
export interface PackageSummary { pendingWorkMinutes?: number; pendingIdleMinutes?: number; pendingOnsiteDays?: number; redFlag?: boolean; disputedCount?: number; transferred?: boolean; id: string; userId: string; employeeNo: string; userName: string; weekStart: string; workItemId: string; workItemName: string; workItemType: string; approverId: string; approverName: string; routeReason: string; approvalDeadline: string; version: number; pendingCount: number; workMinutes: number; idleMinutes: number; onsiteDays: number; canDecide: boolean; canTransfer: boolean }
export interface ApprovalItem { redReason?: string; dayWorkMinutes?: number; projectWorkMinutes?: number; canApprove?: boolean; canReject?: boolean; id: string; kind: 'TIME' | 'ONSITE'; recordId: string; revisionId: string; date: string; action: 'REPORT' | 'CANCEL'; entryKind: EntryKind | null; minutes: number; hours: string; content: string; state: string; current: boolean; handledBy: string | null; handledName: string | null; handledAt: string | null; reason: string; disputeOpen: boolean; canDecide: boolean; canRequestCorrection: boolean; canDispute: boolean; cost?: { rateId?: string; levelHistoryId?: string; amount: string; dailyRate: string; level: string; policyVersion: string }; costError?: string }
export interface CorrectionRequest { id: string; kind: 'TIME' | 'ONSITE'; recordId: string; revisionId: string; userId: string; userName: string; workItemName: string; date: string; action: 'EDIT' | 'CANCEL'; reason: string; state: string; approverId: string; approverName: string; handledAt: string | null; newRevisionId: string | null; canDecide: boolean; canTransfer: boolean }
export interface Dispute { kind?: string; recordId?: string; revisionId?: string; action?: string; entryKind?: string; hours?: string; content?: string; redReason?: string; recordState?: string; openedAt?: string; resolvedAt?: string | null; id: string; approvalItemId: string; packageId: string; userId: string; userName: string; workItemName: string; date: string; state: string; stage: string; coordinatorId: string; coordinatorName: string; employeeStatement: string; approverStatement: string; outcome: string | null; resolutionReason: string | null; canComment: boolean; canResolve: boolean; canEscalate: boolean }
export interface PackageDetail { package: PackageSummary; items: ApprovalItem[]; otherWorkMinutes: number; correctionRequests: CorrectionRequest[]; disputes: Dispute[] }
export interface Designation { id: string; userId: string; userName: string; workItemId: string; workItemName: string; approverId: string; approverName: string; reason: string; basis: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) { super(message); }
}

interface CsrfToken { headerName: string; token: string }
let csrf: CsrfToken | undefined;
export async function request<T>(path: string, init: RequestInit = {}, responseType: 'json' | 'blob' = 'json'): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const writing = init.method && !['GET', 'HEAD'].includes(init.method);
  if (writing) {
    const token = csrf ?? await request<CsrfToken>('/auth/csrf');
    csrf = token;
    headers.set(token.headerName, token.token);
  }
  let response: Response;
  try { response = await fetch(`/api/v1${path}`, { ...init, headers, credentials: 'same-origin' }); }
  catch { throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查网络后重试。当前输入已保留。'); }
  if (response.status === 204) return undefined as T;
  if (response.ok && responseType === 'blob') return await response.blob() as T;
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    if ((response.status === 403 || response.status === 401)) csrf = undefined;
    const fallback = response.status === 401 ? '登录已失效，请重新登录；当前输入已保留。' : response.status === 503 ? '服务暂不可用，请稍后重试；当前输入已保留。' : '请求未完成，请重试。';
    throw new ApiError(response.status, data?.code ?? 'REQUEST_FAILED', data?.message ?? fallback, data?.details);
  }
  if (data === null) throw new ApiError(502, 'INVALID_RESPONSE', '服务返回了无法读取的数据，请重试。');
  return data as T;
}

const uncertainCommands = new Map<string, string>();
export async function command<T>(path: string, body?: unknown): Promise<T> {
  const content = body === undefined ? undefined : JSON.stringify(body);
  const signature = `${path}\n${content ?? ''}`;
  const key = uncertainCommands.get(signature) ?? crypto.randomUUID();
  uncertainCommands.set(signature, key);
  try {
    const result = await request<T>(path, { method: 'POST', body: content, headers: { 'Idempotency-Key': key } });
    uncertainCommands.delete(signature); return result;
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) uncertainCommands.delete(signature);
    throw error;
  }
}

export const api = {
  status: () => request<AuthStatus>('/auth/status'),
  me: () => request<Me>('/me'),
  async login(employeeNo: string) {
    const me = await request<Me>('/auth/local-login', { method: 'POST', body: JSON.stringify({ employeeNo }) });
    csrf = undefined;
    csrf = await request<CsrfToken>('/auth/csrf');
    return me;
  },
  async logout() { await request('/auth/logout', { method: 'POST' }); csrf = undefined; },
  catalog: () => request<Catalog>('/catalog'),
  day: (date: string) => request<Day>(`/days/${date}`),
  week: (start: string) => request<Week>(`/weeks/${start}`),
  saveDay: (date: string, body: DayInput) => request<Day>(`/days/${date}`, { method: 'PUT', body: JSON.stringify(body) }),
  list: <T>(resource: string) => request<T[]>(`/master/${resource}`),
  saveMaster: <T>(resource: string, id: string | null, data: unknown) => request<T>(`/master/${resource}${id ? `/${id}` : ''}`, { method: id ? 'PUT' : 'POST', body: JSON.stringify(data) }),
  calendar: (from: string, to: string) => request<CalendarDay[]>(`/master/calendar?from=${from}&to=${to}`),
  saveCalendar: (day: CalendarDay) => request<CalendarDay>(`/master/calendar/${day.date}`, { method: 'PUT', body: JSON.stringify({ isWorkday: day.isWorkday, baseMinutes: day.baseMinutes, note: day.note }) }),
  importTemplate: (dataset: ImportDataset) => request<Blob>(`/imports/templates/${dataset}`, {}, 'blob'),
  importBatches: () => request<ImportBatchSummary[]>('/imports'),
  importBatch: (id: string) => request<ImportBatch>(`/imports/${id}`),
  previewImport: (dataset: ImportDataset, file: File) => { const body = new FormData(); body.set('file', file); return request<ImportBatch>(`/imports?dataset=${dataset}`, { method: 'POST', body }); },
  applyImport: (id: string) => request<ImportBatch>(`/imports/${id}/apply`, { method: 'POST' }),
  previewWeek: (week: string) => request<WeekPreview>(`/weeks/${week}/preview`, { method: 'POST' }),
  submitWeek: (week: string) => command<BatchResult>(`/weeks/${week}/submit`),
  submitOnsite: (id: string) => command<BatchResult>(`/onsite-days/${id}/submit`),
  packages: (view = 'pending', weekStart = '') => request<PackageSummary[]>(`/approval-packages?view=${encodeURIComponent(view)}${weekStart ? `&weekStart=${weekStart}` : ''}`),
  package: (id: string) => request<PackageDetail>(`/approval-packages/${id}`),
  decide: (id: string, expectedVersion: number, itemIds: string[], decision: 'APPROVE' | 'REJECT', reason: string) => command<BatchResult>(`/approval-packages/${id}/decide`, { expectedVersion, itemIds, decision, reason }),
  transferPackage: (id: string, body: { expectedVersion: number; newApproverId: string; reason: string; basis: string }) => command<PackageDetail>(`/approval-packages/${id}/transfer`, body),
  requestCorrection: (kind: 'TIME' | 'ONSITE', recordId: string, action: 'EDIT' | 'CANCEL', reason: string) => command<CorrectionRequest>('/correction-requests', { kind, recordId, action, reason }),
  corrections: (view = 'all') => request<CorrectionRequest[]>(`/correction-requests?view=${view}`),
  decideCorrection: (id: string, decision: 'APPROVE' | 'DECLINE', reason: string) => command<CorrectionRequest>(`/correction-requests/${id}/decide`, { decision, reason }),
  transferCorrection: (id: string, newApproverId: string, reason: string, basis: string) => command<CorrectionRequest>(`/correction-requests/${id}/transfer`, { newApproverId, reason, basis }),
  disputes: () => request<Dispute[]>('/disputes'),
  createDispute: (approvalItemId: string, statement: string) => command<Dispute>('/disputes', { approvalItemId, statement }),
  commentDispute: (id: string, statement: string) => command<Dispute>(`/disputes/${id}/statements`, { statement }),
  resolveDispute: (id: string, action: 'RESOLVE' | 'ESCALATE', outcome: 'CONFIRM_FACTS' | 'NEEDS_CORRECTION' | null, reason: string) => command<Dispute>(`/disputes/${id}/resolve`, { action, outcome, reason }),
  designations: () => request<Designation[]>('/approval-designations'),
  saveDesignation: (body: { userId: string; workItemId: string; approverId: string; reason: string; basis: string }) => command<Designation>('/approval-designations', body),
};

export const errorMessage = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试。';
export const hours = (minutes: number) => (minutes / 60).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
export function today() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}
export function addDays(date: string, amount: number) {
  const d = new Date(`${date}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + amount); return d.toISOString().slice(0, 10);
}
export function monday(date: string) { const day = new Date(`${date}T12:00:00Z`).getUTCDay(); return addDays(date, -(day || 7) + 1); }
export const freshEntry = (): EntryInput => ({ id: null, workItemId: '', kind: 'WORK', hours: '1', content: '', redReason: '' });
export const entryInput = (entry: EntryInput): EntryInput => ({ ...(entry.correctionRequestId ? { correctionRequestId: entry.correctionRequestId } : {}), id: entry.id, workItemId: entry.workItemId, kind: entry.kind, hours: entry.hours, content: entry.content, redReason: entry.redReason });
export const stateLabel = (day: Day) => !day.enrolled ? '无需填报' : day.entries.length === 0 && !day.onsite ? (day.requiredMinutes ? '未填写' : '休息日') : day.validation.ready ? '填写完整' : '待完善';
export const statusLabel = (value: string) => ({ DRAFT: '草稿', PENDING: '待审批', APPROVED: '已通过', LOCKED: '已锁定', REJECTED: '已驳回', CANCELED: '已取消', REQUESTED: '待核实', DECLINED: '未同意', OPEN: '协调中', ESCALATED: '裁定中', RESOLVED: '已解决', CONFIRM_FACTS: '确认事实', NEEDS_CORRECTION: '需要更正', EDIT: '更正', CANCEL: '取消申报' })[value as 'DRAFT'] ?? value;
export function reviewProgress(day: Day) {
  const records = [...day.entries, ...(day.onsite ? [day.onsite] : [])];
  const pending = records.filter(row => row.state === 'PENDING').length; const rejected = records.filter(row => row.state === 'REJECTED').length;
  const approved = records.filter(row => ['APPROVED', 'LOCKED'].includes(row.state)).length;
  if (rejected) return `${rejected} 项待修改`; if (pending) return `${pending} 项待审批`; if (approved) return `已确认 ${approved}/${records.length} 项`; return records.length ? '尚未送审' : '';
}
export const displayTime = (value: string | null | undefined) => { if(!value)return '暂无'; const stamp=/[zZ]$|[+-]\d{2}:?\d{2}$/.test(value)?value:value.replace(' ','T')+'Z';return new Date(stamp).toLocaleString('zh-CN', { timeZone:'Asia/Shanghai',hour12:false }); };
export function downloadFile(blob: Blob, name: string) { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
