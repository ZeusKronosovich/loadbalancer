import React, { useState, useEffect, useRef } from 'react';
import {
    Grid,
    Paper,
    Box,
    Typography,
    Snackbar,
    Alert,
    CircularProgress
} from '@mui/material';
import StrategySelector from './StrategySelector';
import BackendsTable from './BackendsTable';
import Charts from './Charts';
import StatsPanel from './StatsPanel';
import AddBackendForm from './AddBackendForm';
import WeightManager from './WeightManager';

const normalizeStrategy = (strategy) => {
    if (!strategy) return 'round_robin';
    
    let normalized = strategy.toLowerCase();
    
    if (!normalized.includes('_')) {
        normalized = normalized
            .replace('weightedroundrobin', 'weighted_round_robin')
            .replace('leastconnections', 'least_connections')
            .replace('roundrobin', 'round_robin')
            .replace('consistenthashing', 'consistent_hashing')
            .replace('leastresponsetime', 'least_response_time')
            .replace('leastbandwidth', 'least_bandwidth');
    }
    
    return normalized;
};

function Dashboard({ strategy, backends, stats, isConnected, sendCommand }) {
    const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' });
    const [loading, setLoading] = useState(false);
    
    const [localStrategy, setLocalStrategy] = useState(() => normalizeStrategy(strategy));
    const pendingStrategyRef = useRef(null);
    const renderCountRef = useRef(0);

    renderCountRef.current += 1;

    useEffect(() => {
        const normalizedStrategy = normalizeStrategy(strategy);
        
        if (pendingStrategyRef.current === normalizedStrategy) {
            pendingStrategyRef.current = null;
            setLocalStrategy(normalizedStrategy);
            return;
        }

        if (pendingStrategyRef.current === null) {
            setLocalStrategy(normalizedStrategy);
            return;
        }

        if (pendingStrategyRef.current !== normalizedStrategy) {
            return;
        }

        setLocalStrategy(normalizedStrategy);
    }, [strategy]);

    const handleStrategyChange = async (newStrategy) => {
        if (!isConnected) {
            setSnackbar({
                open: true,
                message: 'Нет соединения с сервером',
                severity: 'error'
            });
            return;
        }

        if (localStrategy === newStrategy) {
            return;
        }

        setLoading(true);
        pendingStrategyRef.current = newStrategy;
        setLocalStrategy(newStrategy);
        
        try {
            sendCommand('changeStrategy', { strategy: newStrategy });
            
            setSnackbar({
                open: true,
                message: `Стратегия изменена на ${newStrategy.replace('_', ' ')}`,
                severity: 'success'
            });
        } catch (error) {
            pendingStrategyRef.current = null;
            setLocalStrategy(normalizeStrategy(strategy));
            setSnackbar({
                open: true,
                message: `Ошибка: ${error.message}`,
                severity: 'error'
            });
        } finally {
            setLoading(false);
        }
    };

    const handleBackendChange = () => {
        setSnackbar({
            open: true,
            message: 'Список бэкендов обновлён',
            severity: 'success'
        });
    };

    const handleCloseSnackbar = () => {
        setSnackbar({ ...snackbar, open: false });
    };

    const calculateAverageResponseTime = () => {
        if (!backends || backends.length === 0) return null;
        const aliveBackends = backends.filter(b => b.alive && b.averageResponseTime > 0);
        if (aliveBackends.length === 0) return null;
        const sum = aliveBackends.reduce((acc, b) => acc + b.averageResponseTime, 0);
        return Math.round(sum / aliveBackends.length);
    };

    const avgRT = calculateAverageResponseTime();
    const isWeightedStrategy = localStrategy === 'weighted_round_robin';

    if (!backends || backends.length === 0) {
        return (
            <div className="dashboard-loading">
                <CircularProgress />
                <Typography className="dashboard-loading-text">
                    Ожидание данных от сервера...
                </Typography>
            </div>
        );
    }

    return (
        <>
            <Grid container spacing={3}>
                <Grid item xs={12}>
                    <StatsPanel stats={{ ...stats, averageResponseTimeAll: avgRT }} />
                </Grid>

                <Grid item xs={12} md={isWeightedStrategy ? 6 : 12}>
                    <Paper elevation={3} sx={{ p: 3 }}>
                        <StrategySelector
                            currentStrategy={localStrategy}
                            onStrategyChange={handleStrategyChange}
                            loading={loading}
                            isConnected={isConnected}
                        />
                    </Paper>
                </Grid>

                {isWeightedStrategy && (
                    <Grid item xs={12} md={6} className="weight-fade-in">
                        <WeightManager
                            backends={backends}
                            currentStrategy={localStrategy}
                            onWeightChange={handleBackendChange}
                        />
                    </Grid>
                )}

                <Grid item xs={12}>
                    <Paper elevation={3} sx={{ p: 3 }}>
                        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1 }}>
                            <Typography variant="h6">
                                Бэкенды
                            </Typography>
                        </Box>
                        <BackendsTable backends={backends} />
                    </Paper>
                </Grid>

                <Grid item xs={12}>
                    <AddBackendForm onBackendChange={handleBackendChange} />
                </Grid>

                <Grid item xs={12} md={6}>
                    <Charts backends={backends} type="distribution" />
                </Grid>

                <Grid item xs={12} md={6}>
                    <Charts backends={backends} type="history" />
                </Grid>

                {localStrategy === 'least_response_time' && (
                    <Grid item xs={12}>
                        <Charts backends={backends} type="responseTime" />
                    </Grid>
                )}

                {localStrategy === 'least_bandwidth' && (
                    <Grid item xs={12}>
                        <Charts backends={backends} type="bandwidth" />
                    </Grid>
                )}
            </Grid>

            <Snackbar
                open={snackbar.open}
                autoHideDuration={3000}
                onClose={handleCloseSnackbar}
                anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            >
                <Alert
                    onClose={handleCloseSnackbar}
                    severity={snackbar.severity}
                    className="snackbar-alert"
                >
                    {snackbar.message}
                </Alert>
            </Snackbar>
        </>
    );
}

export default Dashboard;