import { useState, useEffect, useRef } from 'react';
import wsService from '../services/websocket';

export function useWebSocket() {
    const [isConnected, setIsConnected] = useState(false);
    const [lastMessage, setLastMessage] = useState(null);
    const [data, setData] = useState({
        strategy: 'round_robin',
        backends: [],
        stats: {
            totalRequests: 0,
            successfulRequests: 0,
            failedRequests: 0,
            successRate: '0',
            activeConnectionsTotal: 0,
            uptimeSeconds: 0
        }
    });
    const listenerRef = useRef(null);

    useEffect(() => {
        const handleMessage = (message) => {
            setLastMessage(message);
            if (message.strategy) {
                setData(prev => ({ ...prev, strategy: message.strategy }));
            }
            if (message.backends) {
                setData(prev => ({ ...prev, backends: message.backends }));
            }
            if (message.stats) {
                setData(prev => ({ ...prev, stats: message.stats }));
            }
        };

        listenerRef.current = handleMessage;
        wsService.addListener(handleMessage);

        wsService.connect()
            .then(() => setIsConnected(true))
            .catch(() => setIsConnected(false));

        const interval = setInterval(() => {
            setIsConnected(wsService.isConnected());
        }, 2000);

        return () => {
            if (listenerRef.current) {
                wsService.removeListener(listenerRef.current);
            }
            clearInterval(interval);
            wsService.disconnect();
        };
    }, []);

    const sendCommand = (command, data) => {
        wsService.send({ command, ...data });
    };

    return {
        isConnected,
        lastMessage,
        data,
        sendCommand
    };
}