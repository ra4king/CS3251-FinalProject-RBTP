#! /usr/bin/env python

# Client and server for udp (datagram) echo.
# 
# based on udpecho.py from http://www.opensource.apple.com/source/python/python-3/python/Demo/sockets/udpecho.py
#
# Usage: udpecho -s [port]            (to start a server)
# or:    udpecho -c host [port] <file (client)

import sys
from socket import *

ECHO_PORT = 50000 + 7
BUFSIZE = 1024

def main():
	if len(sys.argv) < 2:
		usage()
	if sys.argv[1] == '-s':
		server()
	elif sys.argv[1] == '-c':
		client()
	else:
		usage()

def usage():
	sys.stdout = sys.stderr
	print 'Usage: udpecho -s [port] (server)'
	print 'or:    udpecho -c bindport desthost [destport] <file (client)'
	sys.exit(2)

def server():
	if len(sys.argv) > 2:
		port = eval(sys.argv[2])
	else:
		port = ECHO_PORT
	s = socket(AF_INET, SOCK_DGRAM)
	s.bind(('', port))
	print 'udp echo server ready'
	while 1:
		data, addr = s.recvfrom(BUFSIZE)
		print 'server received', `data`, 'from', `addr`
		s.sendto(data, addr)

def client():
	if len(sys.argv) < 4:
		usage()
        bindport = eval(sys.argv[2])
	host = sys.argv[3]
	if len(sys.argv) > 4:
		port = eval(sys.argv[4])
	else:
		port = ECHO_PORT
	addr = host, port
	s = socket(AF_INET, SOCK_DGRAM)
	s.bind(('', bindport))
	print 'udp echo client ready, reading stdin'
	while 1:
		line = sys.stdin.readline()
		if not line:
			break
		print 'client sending', `line`, 'to', `addr`
		s.sendto(line, addr)
		data, fromaddr = s.recvfrom(BUFSIZE)
		print 'client received', `data`, 'from', `fromaddr`

main()
