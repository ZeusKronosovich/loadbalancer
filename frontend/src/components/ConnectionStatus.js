import React from 'react';
import { Chip, Tooltip } from '@mui/material';

function ConnectionStatus({ isConnected }) {
    const status = isConnected ? 'connected' : 'disconnected';
    const labels = {
        connected: ' Online',
        disconnected: ' Offline'
    };
    const colors = {
        connected: 'success',
        disconnected: 'error'
    };

    const dotClass = `status-dot status-dot-${status}`;

    return (
        <Tooltip title={isConnected ? 'Соединение установлено' : 'Нет соединения с сервером'}>
            <Chip
                icon={<span className={dotClass} />}
                label={labels[status]}
                color={colors[status]}
                size="small"
                variant="outlined"
                sx={{ fontWeight: 600 }}
            />
        </Tooltip>
    );
}

export default ConnectionStatus;