import React, { useState, useEffect } from 'react';
import {
    Paper,
    Typography,
    TextField,
    Button,
    Box,
    Alert,
    Fade,
    Chip,
    Slider,
    Tooltip,
    IconButton
} from '@mui/material';
import { Settings } from '@mui/icons-material';
import { setWeight } from '../services/api';

function WeightManager({ backends, onWeightChange }) {
    const [selectedBackend, setSelectedBackend] = useState(null);
    const [weight, setWeightValue] = useState(1);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [success, setSuccess] = useState(null);
    const [showAllWeights, setShowAllWeights] = useState(false);
    const [localWeights, setLocalWeights] = useState({});

    useEffect(() => {
        const weights = {};
        backends.forEach(b => {
            const key = `${b.host}:${b.port}`;
            weights[key] = b.weight || 1;
        });
        setLocalWeights(weights);
    }, [backends]);

    const handleSetWeight = async () => {
        if (!selectedBackend) return;
        setError(null);
        setSuccess(null);
        setLoading(true);

        try {
            await setWeight(selectedBackend.host, selectedBackend.port, weight);
            
            const key = `${selectedBackend.host}:${selectedBackend.port}`;
            setLocalWeights(prev => ({ ...prev, [key]: weight }));
            
            setSuccess(`Вес для ${selectedBackend.host}:${selectedBackend.port} установлен на ${weight}`);
            if (onWeightChange) onWeightChange();
            
            setTimeout(() => setSuccess(null), 3000);
        } catch (err) {
            setError(err.response?.data?.error || 'Ошибка установки веса');
        } finally {
            setLoading(false);
        }
    };

    const handleSelectBackend = (backend) => {
        setSelectedBackend(backend);
        const key = `${backend.host}:${backend.port}`;
        setWeightValue(localWeights[key] || 1);
    };

    const getCurrentWeight = (backend) => {
        const key = `${backend.host}:${backend.port}`;
        return localWeights[key] || 1;
    };

    return (
        <Paper elevation={2} className="weight-manager-container">
            <div className="weight-manager-header">
                <Typography variant="h6">
                    Управление весами
                </Typography>
                <Box>
                    <Tooltip title="Показать веса всех бэкендов">
                        <IconButton onClick={() => setShowAllWeights(!showAllWeights)}>
                            <Settings />
                        </IconButton>
                    </Tooltip>
                </Box>
            </div>

            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
                Настройка весов для стратегии <strong>Weighted Round Robin</strong>
            </Typography>

            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {backends.filter(b => b.alive).map((b) => {
                    const weight = getCurrentWeight(b);
                    return (
                        <Chip
                            key={`${b.host}:${b.port}`}
                            label={`${b.host}:${b.port} (${weight})`}
                            onClick={() => handleSelectBackend(b)}
                            color={selectedBackend?.port === b.port ? 'primary' : 'default'}
                            variant={selectedBackend?.port === b.port ? 'filled' : 'outlined'}
                            className="weight-chip"
                        />
                    );
                })}
            </Box>

            {selectedBackend && (
                <Box sx={{ mt: 3 }}>
                    <Typography variant="body2" gutterBottom>
                        Вес для <strong>{selectedBackend.host}:{selectedBackend.port}</strong>
                        {' '}(текущий: <strong>{getCurrentWeight(selectedBackend)}</strong>)
                    </Typography>
                    
                    <div className="weight-controls">
                        <Slider
                            value={weight}
                            onChange={(_, val) => setWeightValue(val)}
                            min={1}
                            max={10}
                            step={1}
                            marks={[
                                { value: 1, label: '1' },
                                { value: 5, label: '5' },
                                { value: 10, label: '10' },
                            ]}
                            valueLabelDisplay="auto"
                            className="weight-slider"
                            disabled={loading}
                        />
                        <TextField
                            value={weight}
                            onChange={(e) => {
                                const val = parseInt(e.target.value) || 1;
                                setWeightValue(Math.min(Math.max(val, 1), 10));
                            }}
                            size="small"
                            type="number"
                            className="weight-input"
                            inputProps={{ min: 1, max: 10 }}
                            disabled={loading}
                        />
                        <Button
                            variant="contained"
                            onClick={handleSetWeight}
                            disabled={loading}
                            className="weight-apply-button"
                        >
                            Применить
                        </Button>
                    </div>
                </Box>
            )}

            {showAllWeights && (
                <div className="weight-list-container">
                    <Typography variant="subtitle2" gutterBottom>
                        Текущие веса:
                    </Typography>
                    {backends.map(b => {
                        const weight = getCurrentWeight(b);
                        return (
                            <div key={`${b.host}:${b.port}`} className="weight-list-item">
                                <Typography variant="body2">
                                    {b.host}:{b.port}
                                    {!b.alive && ' (недоступен)'}
                                </Typography>
                                <Chip 
                                    label={weight} 
                                    size="small" 
                                    color={b.alive ? 'primary' : 'default'} 
                                />
                            </div>
                        );
                    })}
                </div>
            )}

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

export default WeightManager;