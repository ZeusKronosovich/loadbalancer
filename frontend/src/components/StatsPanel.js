import React from 'react';
import {
    Paper,
    Grid,
    Typography,
    Box,
    Tooltip
} from '@mui/material';
import {
    TrendingUp,
    TrendingDown,
    CheckCircle,
    Error,
    Timer,
    Speed
} from '@mui/icons-material';

function StatsPanel({ stats }) {
    const {
        totalRequests = 0,
        successfulRequests = 0,
        failedRequests = 0,
        successRate = '0',
        activeConnectionsTotal = 0,
        uptimeSeconds = 0,
        rps = 0
    } = stats || {};

    const formatUptime = (seconds) => {
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        if (h > 0) return `${h}ч ${m}м ${s}с`;
        if (m > 0) return `${m}м ${s}с`;
        return `${s}с`;
    };

    const statCards = [
        {
            title: 'Всего запросов',
            value: totalRequests.toLocaleString(),
            icon: <Speed sx={{ fontSize: 28 }} />,
            color: '#3b82f6',
            tooltip: 'Общее количество обработанных запросов'
        },
        {
            title: 'Успешно',
            value: successfulRequests.toLocaleString(),
            icon: <CheckCircle sx={{ fontSize: 28 }} />,
            color: '#22c55e',
            tooltip: 'Запросы, завершившиеся успешно'
        },
        {
            title: 'Ошибок',
            value: failedRequests.toLocaleString(),
            icon: <Error sx={{ fontSize: 28 }} />,
            color: '#ef4444',
            tooltip: 'Запросы, завершившиеся ошибкой'
        },
        {
            title: 'Успешность',
            value: `${successRate}%`,
            icon: <TrendingUp sx={{ fontSize: 28 }} />,
            color: successRate > 90 ? '#22c55e' : successRate > 70 ? '#f59e0b' : '#ef4444',
            tooltip: 'Процент успешных запросов'
        },
        {
            title: 'Активных соединений',
            value: activeConnectionsTotal,
            icon: <TrendingDown sx={{ fontSize: 28 }} />,
            color: '#8b5cf6',
            tooltip: 'Текущее количество активных соединений'
        },
        {
            title: 'Время работы',
            value: formatUptime(uptimeSeconds),
            icon: <Timer sx={{ fontSize: 28 }} />,
            color: '#f59e0b',
            tooltip: 'Время работы балансировщика'
        },
        {
            title: 'RPS',
            value: rps > 0 ? Math.round(rps * 10) / 10 : 0,
            icon: <Speed sx={{ fontSize: 28 }} />,
            color: '#ec4899',
            tooltip: 'Запросов в секунду (Request Per Second)'
        },
    ];

    return (
        <Grid container spacing={2}>
            {statCards.map((card, index) => (
                <Grid item xs={12} sm={6} md={3} key={index}>
                    <Tooltip title={card.tooltip || ''} arrow>
                        <Paper className="stats-card" elevation={2}>
                            <div 
                                className="stats-icon-box"
                                style={{ 
                                    backgroundColor: `${card.color}15`,
                                    color: card.color
                                }}
                            >
                                {card.icon}
                            </div>
                            <Box>
                                <Typography className="stats-title">
                                    {card.title}
                                </Typography>
                                <Typography className="stats-value">
                                    {card.value}
                                </Typography>
                            </Box>
                        </Paper>
                    </Tooltip>
                </Grid>
            ))}
        </Grid>
    );
}

export default StatsPanel;