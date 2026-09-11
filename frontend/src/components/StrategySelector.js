import React from 'react';
import {
    Box,
    Button,
    Typography,
    Chip,
    CircularProgress,
    Tooltip,
    Badge
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import WifiOffIcon from '@mui/icons-material/WifiOff';
import SpeedIcon from '@mui/icons-material/Speed';
import RouteIcon from '@mui/icons-material/Route';
import TimerIcon from '@mui/icons-material/Timer';
import NetworkCheckIcon from '@mui/icons-material/NetworkCheck';
import ScaleIcon from '@mui/icons-material/Scale';

const strategies = [
    { 
        id: 'round_robin', 
        label: 'Round Robin', 
        description: 'Равномерное распределение по очереди',
        icon: <RouteIcon />
    },
    { 
        id: 'least_connections', 
        label: 'Least Connections', 
        description: 'На бэкенд с наименьшим числом соединений',
        icon: <SpeedIcon />
    },
    { 
        id: 'consistent_hashing', 
        label: 'Consistent Hashing', 
        description: 'Привязка клиента к бэкенду (липкие сессии)',
        icon: <TimerIcon />
    },
    { 
        id: 'weighted_round_robin', 
        label: 'Weighted RR', 
        description: 'Round Robin с настраиваемыми весами',
        icon: <ScaleIcon />,
        color: 'warning'
    },
    { 
        id: 'least_response_time', 
        label: 'Least Response Time', 
        description: 'Выбор бэкенда с наименьшим средним временем ответа',
        icon: <SpeedIcon />,
        color: 'secondary'
    },
    { 
        id: 'least_bandwidth', 
        label: 'Least Bandwidth', 
        description: 'Выбор бэкенда с наименьшей текущей пропускной способностью',
        icon: <NetworkCheckIcon />,
        color: 'info'
    },
];

function StrategySelector({ currentStrategy, onStrategyChange, loading, isConnected }) {
    const handleClick = (strategyId) => {
        if (loading || !isConnected) return;
        onStrategyChange(strategyId);
    };

    const currentStrategyInfo = strategies.find(s => s.id === currentStrategy);

    const getButtonColor = (strategy) => {
        if (currentStrategy !== strategy.id) return 'primary';
        return strategy.color || 'primary';
    };

    return (
        <Box>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                <Typography variant="h6" sx={{ mr: 2 }}>
                    Стратегия:
                </Typography>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                    {strategies.map((s) => (
                        <Tooltip key={s.id} title={s.description} arrow>
                            <Badge
                                badgeContent={s.badge}
                                color="secondary"
                                invisible={!s.badge || currentStrategy === s.id}
                                sx={{
                                    '& .MuiBadge-badge': {
                                        fontSize: '10px',
                                        height: '20px',
                                        minWidth: '20px',
                                        padding: '0 6px',
                                        animation: 'pulse 2s infinite',
                                    }
                                }}
                            >
                                <Button
                                    variant={currentStrategy === s.id ? 'contained' : 'outlined'}
                                    color={getButtonColor(s)}
                                    onClick={() => handleClick(s.id)}
                                    startIcon={s.icon}
                                    disabled={loading || !isConnected}
                                    className="strategy-button"
                                    sx={{
                                        fontWeight: currentStrategy === s.id ? 600 : 400,
                                    }}
                                >
                                    {s.label}
                                    {currentStrategy === s.id && (
                                        <CheckCircleIcon sx={{ ml: 1, fontSize: 16 }} />
                                    )}
                                    {loading && currentStrategy === s.id && (
                                        <CircularProgress size={16} sx={{ ml: 1 }} />
                                    )}
                                </Button>
                            </Badge>
                        </Tooltip>
                    ))}
                </Box>
                {!isConnected && (
                    <Chip
                        icon={<WifiOffIcon />}
                        label="Нет соединения"
                        color="error"
                        size="small"
                    />
                )}
            </Box>
        </Box>
    );
}

export default StrategySelector;