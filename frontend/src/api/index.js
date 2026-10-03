import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 10000 })

http.interceptors.response.use(
  response => {
    const payload = response.data
    if (!payload?.ok) throw new Error(payload?.message || '请求失败')
    return payload.data
  },
  error => Promise.reject(new Error(error.response?.data?.message || error.message || '网络请求失败'))
)

export const farmApi = {
  getOverview: () => http.get('/rooms/overview'),
  getRooms: () => http.get('/rooms'),
  changeRoomStatus: (roomId, status) => http.put(`/rooms/${roomId}/status`, { status }),
  getEnvironmentDashboard: () => http.get('/environments/dashboard'),
  getEnvironmentHistory: (roomId, hours = 24) => http.get(`/environments/rooms/${roomId}/history`, { params: { hours } }),
  getEnvironmentRecords: params => http.get('/environments', { params }),
  addEnvironmentRecord: data => http.post('/environments', data),
  getTickets: status => http.get('/environment-tickets', { params: status ? { status } : {} }),
  claimTicket: (id, data) => http.post(`/environment-tickets/${id}/claim`, data),
  addTicketNote: (id, data) => http.post(`/environment-tickets/${id}/notes`, data),
  resolveTicket: (id, data) => http.post(`/environment-tickets/${id}/resolve`, data),
  getBags: status => http.get('/bags', { params: status ? { status } : {} }),
  getBagSummary: () => http.get('/bags/summary'),
  openBag: id => http.post(`/bags/${id}/open`),
  getAllocationBoard: () => http.get('/bag-allocations/board'),
  createAllocation: data => http.post('/bag-allocations', data),
  revokeAllocation: (id, data) => http.post(`/bag-allocations/${id}/revoke`, data),
  getFruitingBatches: () => http.get('/fruiting-batches'),
  getFlushes: batchId => http.get(`/fruiting-batches/${batchId}/flushes`),
  harvest: (batchId, flushNo, data) => http.post(`/fruiting-batches/${batchId}/flushes/${flushNo}/harvest`, data),
  getRoomYields: () => http.get('/fruiting-batches/room-yields'),
  getContaminations: () => http.get('/contaminations'),
  getContaminationSummary: () => http.get('/contaminations/summary'),
  registerContamination: data => http.post('/contaminations', data),
  discardBag: bagBatchId => http.post(`/contaminations/bags/${bagBatchId}/discard`),
  getRacks: roomId => http.get(`/rooms/${roomId}/racks`),
  shelfBag: (rackId, data) => http.post(`/racks/${rackId}/slots`, data)
}
