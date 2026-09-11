import React, { useState } from 'react';
import {
    Paper,
    Typography,
    TextField,
    Button,
    Box,
    Alert,
    Fade,
    InputAdornment
} from '@mui/material';
import { Add } from '@mui/icons-material';
import { addBackend } from '../services/api';

function AddBackendForm({ onBackendChange }) {
    const [host, setHost] = useState('localhost');
    const [port, setPort] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [success, setSuccess] = useState(null);

    const handleAdd = async (e) => {
        e.preventDefault();
        setError(null);
        setSuccess(null);
        setLoading(true);

        const portNum = parseInt(port);
        if (!portNum || portNum < 1 || portNum > 65535) {
            setError('Введите корректный номер порта (1-65535)');
            setLoading(false);
            return;
        }

        try {
            await addBackend(host, portNum);

            setPort('');
            if (onBackendChange) onBackendChange();
            
            setTimeout(() => setSuccess(null), 3000);
        } catch (err) {
            setError(err.response?.data?.error || 'Ошибка добавления бэкенда');
        } finally {
            setLoading(false);
        }
    };

    return (
        <Paper elevation={2} sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>
                Управление бэкендами
            </Typography>

            <Box component="form" onSubmit={handleAdd} className="add-backend-form">
                <TextField
                    label="Хост"
                    value={host}
                    onChange={(e) => setHost(e.target.value)}
                    size="small"
                    className="add-backend-host-input"
                    disabled={loading}
                />
                <TextField
                    label="Порт"
                    value={port}
                    onChange={(e) => setPort(e.target.value)}
                    size="small"
                    type="number"
                    className="add-backend-port-input"
                    disabled={loading}
                    InputProps={{
                        inputProps: { min: 1, max: 65535 },
                        startAdornment: (
                            <InputAdornment position="start">:</InputAdornment>
                        ),
                    }}
                />
                <Button
                    type="submit"
                    variant="contained"
                    startIcon={<Add />}
                    disabled={loading || !port}
                    className="add-backend-submit"
                >
                    Добавить
                </Button>
            </Box>

            <Fade in={!!error || !!success}>
                <Box sx={{ mt: 2 }}>
                    {error && (
                        <Alert severity="error" onClose={() => setError(null)}>
                            {error}
                        </Alert>
                    )}
                    {success && (
                        <Alert severity="success" onClose={() => setSuccess(null)}>
                            {success}
                        </Alert>
                    )}
                </Box>
            </Fade>
        </Paper>
    );
}

export default AddBackendForm;