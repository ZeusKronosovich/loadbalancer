import axios from 'axios';

const api = axios.create({
    baseURL: '/api',
    headers: {
        'Content-Type': 'application/json',
    },
    timeout: 5000,
});

export const getStrategy = () => api.get('/strategy');
export const setStrategy = (type) => api.post('/strategy', { type });
export const getBackends = () => api.get('/backends');
export const getStats = () => api.get('/stats');
export const addBackend = (host, port) => api.post('/backends', { host, port });
export const removeBackend = (host, port) => api.delete('/backends', { data: { host, port } });
export const setWeight = (host, port, weight) => api.post('/weights', { host, port, weight });

export default api;