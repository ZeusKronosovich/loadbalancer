import React, { useState } from 'react';
import {
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    Chip,
    Box,
    IconButton,
    Tooltip,
    Dialog,
    DialogTitle,
    DialogContent,
    DialogActions,
    Button,
    Typography
} from '@mui/material';
import { 
    Delete, 
    Warning, 
    Speed, 
    AccessTime, 
    NetworkCheck
} from '@mui/icons-material';
import { removeBackend } from '../services/api';

function BackendsTable({ backends }) {
    const [deleteTarget, setDeleteTarget] = useState(null);
    const [deleting, setDeleting] = useState(false);

    const handleDeleteClick = (backend) => {
        setDeleteTarget(backend);
    };

    const handleDeleteConfirm = async () => {
        if (!deleteTarget) return;
        setDeleting(true);
        try {
            await removeBackend(deleteTarget.host, deleteTarget.port);
            setDeleteTarget(null);
        } catch (error) {
            console.error('Error deleting backend:', error);
        } finally {
            setDeleting(false);
        }
    };

    const handleDeleteCancel = () => {
        setDeleteTarget(null);
    };

    const formatResponseTime = (avgTime) => {
        if (!avgTime || avgTime === 0) return '—';
        if (avgTime < 1) return '<1ms';
        if (avgTime < 10) return `${avgTime.toFixed(1)}ms`;
        if (avgTime < 1000) return `${Math.round(avgTime)}ms`;
        return `${(avgTime / 1000).toFixed(1)}s`;
    };

    const getResponseTimeColor = (avgTime) => {
        if (!avgTime || avgTime === 0) return 'default';
        if (avgTime < 50) return 'success';
        if (avgTime < 200) return 'warning';
        return 'error';
    };

    const formatBandwidth = (bytesPerSecond) => {
        if (!bytesPerSecond || bytesPerSecond === 0) return '—';
        if (bytesPerSecond < 1024) return `${Math.round(bytesPerSecond)} B/s`;
        if (bytesPerSecond < 1024 * 1024) return `${(bytesPerSecond / 1024).toFixed(1)} KB/s`;
        return `${(bytesPerSecond / (1024 * 1024)).toFixed(1)} MB/s`;
    };

    const getBandwidthColor = (bandwidth) => {
        if (!bandwidth || bandwidth === 0) return 'default';
        if (bandwidth < 1024 * 100) return 'success';
        if (bandwidth < 1024 * 1024) return 'warning';
        return 'error';
    };

    return (
        <>
            <TableContainer>
                <Table>
                    <TableHead>
                        <TableRow>
                            <TableCell><strong>Хост</strong></TableCell>
                            <TableCell><strong>Порт</strong></TableCell>
                            <TableCell><strong>Статус</strong></TableCell>
                            <TableCell align="center"><strong>Активные соединения</strong></TableCell>
                            <TableCell align="center"><strong>Вес</strong></TableCell>
                            <TableCell align="center"><strong>Среднее время ответа</strong></TableCell>
                            <TableCell align="center"><strong>Текущая пропускная способность</strong></TableCell>
                            <TableCell align="center"><strong>Всего запросов</strong></TableCell>
                            <TableCell align="center"><strong>Действия</strong></TableCell>
                        </TableRow>
                    </TableHead>
                    <TableBody>
                        {backends.map((b) => {
                            const avgRT = b.averageResponseTime || 0;
                            const bandwidth = b.currentBandwidth || 0;
                            const totalRequests = b.totalRequests || 0;
                            
                            return (
                                <TableRow 
                                    key={`${b.host}:${b.port}`} 
                                    hover
                                    className={`backends-table-row ${!b.alive ? 'backends-table-row-inactive' : ''}`}
                                >
                                    <TableCell>{b.host}</TableCell>
                                    <TableCell>{b.port}</TableCell>
                                    <TableCell>
                                        <Chip
                                            label={b.alive ? 'Alive' : 'Dead'}
                                            color={b.alive ? 'success' : 'error'}
                                            size="small"
                                        />
                                    </TableCell>
                                    <TableCell align="center">
                                        <Chip
                                            label={b.activeConnections}
                                            color={b.activeConnections > 10 ? 'warning' : 'default'}
                                            size="medium"
                                        />
                                    </TableCell>
                                    <TableCell align="center">
                                        <Chip
                                            label={b.weight || 1}
                                            color="primary"
                                            size="small"
                                            variant="outlined"
                                        />
                                    </TableCell>
                                    <TableCell align="center">
                                        <Tooltip 
                                            title={
                                                avgRT > 0 
                                                    ? `Среднее время ответа: ${formatResponseTime(avgRT)}`
                                                    : 'Нет данных о времени ответа'
                                            }
                                            arrow
                                        >
                                            <Chip
                                                icon={<Speed sx={{ fontSize: 16 }} />}
                                                label={formatResponseTime(avgRT)}
                                                color={getResponseTimeColor(avgRT)}
                                                size="small"
                                                variant={avgRT > 0 ? 'filled' : 'outlined'}
                                                sx={{ minWidth: 80 }}
                                            />
                                        </Tooltip>
                                    </TableCell>
                                    <TableCell align="center">
                                        <Tooltip 
                                            title={
                                                bandwidth > 0 
                                                    ? `Текущая пропускная способность: ${formatBandwidth(bandwidth)}\nВсего передано: ${formatBandwidth(b.totalBytesTransferred || 0)}`
                                                    : 'Нет данных о трафике'
                                            }
                                            arrow
                                        >
                                            <Chip
                                                icon={<NetworkCheck sx={{ fontSize: 16 }} />}
                                                label={formatBandwidth(bandwidth)}
                                                color={getBandwidthColor(bandwidth)}
                                                size="small"
                                                variant={bandwidth > 0 ? 'filled' : 'outlined'}
                                                sx={{ minWidth: 100 }}
                                            />
                                        </Tooltip>
                                    </TableCell>
                                    <TableCell align="center">
                                        <Tooltip title={`Всего обработано запросов: ${totalRequests}`}>
                                            <Chip
                                                icon={<AccessTime sx={{ fontSize: 14 }} />}
                                                label={totalRequests > 0 ? totalRequests : '0'}
                                                size="small"
                                                variant="outlined"
                                                color={totalRequests > 100 ? 'primary' : 'default'}
                                            />
                                        </Tooltip>
                                    </TableCell>
                                    <TableCell align="center">
                                        <Tooltip title="Удалить бэкенд">
                                            <IconButton
                                                size="small"
                                                color="error"
                                                onClick={() => handleDeleteClick(b)}
                                                disabled={!b.alive}
                                            >
                                                <Delete />
                                            </IconButton>
                                        </Tooltip>
                                    </TableCell>
                                </TableRow>
                            );
                        })}
                    </TableBody>
                </Table>
            </TableContainer>

            {backends.length === 0 && (
                <div className="backends-table-empty">
                    <Typography color="text.secondary">
                        Нет добавленных бэкендов
                    </Typography>
                </div>
            )}

            <Dialog open={!!deleteTarget} onClose={handleDeleteCancel}>
                <DialogTitle>
                    <div className="dialog-delete-title">
                        <Warning color="warning" />
                        Подтверждение удаления
                    </div>
                </DialogTitle>
                <DialogContent>
                    <Typography>
                        Вы уверены, что хотите удалить бэкенд{' '}
                        <strong>{deleteTarget?.host}:{deleteTarget?.port}</strong>?
                    </Typography>
                </DialogContent>
                <DialogActions>
                    <Button onClick={handleDeleteCancel} disabled={deleting}>
                        Отмена
                    </Button>
                    <Button 
                        onClick={handleDeleteConfirm} 
                        color="error" 
                        variant="contained"
                        disabled={deleting}
                    >
                        {deleting ? 'Удаление...' : 'Удалить'}
                    </Button>
                </DialogActions>
            </Dialog>
        </>
    );
}

export default BackendsTable;